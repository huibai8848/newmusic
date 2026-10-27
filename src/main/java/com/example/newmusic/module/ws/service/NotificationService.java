package com.example.newmusic.module.ws.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知的落库、推送与已读（FR-13-005）。
 *
 * <p><b>顺序是硬约束：先落库、再推送</b>（BR-13-027）。反过来「推送成功、落库失败」
 * 会给在线用户显示一条刷新后消失的通知；而先落库后推送失败只是「晚点通过 HTTP 拉到」，
 * 这是 EX-13-005-3 明确接受的结果。
 *
 * <p>未读数以 DB 为准、Redis 只是缓存（BR-13-031）：推送时 INCR 是快路径；
 * 已读操作后重算 DB 再回写缓存，避免「读了一次就永久偏离」。
 */
@Slf4j
@Service
public class NotificationService {

    /** 未读缓存 TTL；用户三个月不登录也就没有缓存的意义了 */
    private static final Duration UNREAD_TTL = Duration.ofDays(7);

    private final NotificationMapper notificationMapper;
    private final WsPushService pushService;
    private final RedisOps redisOps;

    public NotificationService(NotificationMapper notificationMapper, WsPushService pushService,
                               RedisOps redisOps) {
        this.notificationMapper = notificationMapper;
        this.pushService = pushService;
        this.redisOps = redisOps;
    }

    /**
     * 创建并推送一条通知。
     *
     * <p>落库失败**不推送**（EX-13-005-3）：异常抛给调用方，由它决定是重试还是记录。
     * 推送失败只记 WARN，不影响接口结果——通知已经落库，用户可以拉取到。
     */
    public Notification createAndPush(Notification notification) {
        if (notification.getIsRead() == null) {
            notification.setIsRead(0);
        }
        if (notification.getCreateTime() == null) {
            notification.setCreateTime(LocalDateTime.now());
        }
        notificationMapper.insert(notification);
        pushExisting(notification);
        return notification;
    }

    /** 推送一条**已经落库**的通知（重推、或其它模块先批量插入后再统一推送）。 */
    public void pushExisting(Notification notification) {
        // 以 DB 为准重算未读：Redis INCR 在「缓存冷、库里已有未读」时会从 1 开始，少算一截。
        // 推送不是热点路径，多一次走索引的 COUNT 换取一致性是值得的。
        long unread = refreshUnreadCount(notification.getUserId());
        try {
            pushService.sendToUser(notification.getUserId(), wsType(notification.getNotifyType()),
                    payload(notification));
            pushService.sendToUser(notification.getUserId(), "notify.unread",
                    single("unread", unread));
        } catch (Exception e) {
            log.warn("通知推送失败（已落库，可经 HTTP 拉取） notificationId={} userId={} cause={}",
                    notification.getId(), notification.getUserId(), e.toString());
        }
    }

    public long unreadCount(Long userId) {
        String key = RedisKeys.notifyUnread(userId);
        java.util.Optional<String> cached = redisOps.get(key);
        if (cached.isPresent()) {
            try {
                return Long.parseLong(cached.get());
            } catch (NumberFormatException ignored) {
                // 脏值按缓存未命中处理
            }
        }
        long count = notificationMapper.countUnread(userId);
        redisOps.set(key, String.valueOf(count), UNREAD_TTL);
        return count;
    }

    public PageResult<Notification> page(Long userId, int pageNum, int pageSize) {
        return page(userId, null, pageNum, pageSize);
    }

    /** 按分类过滤的通知分页；{@code types} 为空表示全部。 */
    public PageResult<Notification> page(Long userId, List<Integer> types, int pageNum, int pageSize) {
        Page<Notification> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<Notification> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (types != null && !types.isEmpty()) {
            wrapper.in("notify_type", types);
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return PageResult.of(notificationMapper.selectPage(page, wrapper));
    }

    /** 清空当前用户全部通知（物理删除；通知是追加型数据，没有恢复语义）。 */
    @Transactional
    public int clearAll(Long userId) {
        int deleted = notificationMapper.delete(new QueryWrapper<Notification>().eq("user_id", userId));
        refreshUnread(userId);
        return deleted;
    }

    /** 删除单条；返回 false 表示不存在或不属于该用户。 */
    @Transactional
    public boolean deleteOne(Long userId, Long id) {
        int deleted = notificationMapper.delete(new QueryWrapper<Notification>()
                .eq("user_id", userId).eq("id", id));
        refreshUnread(userId);
        return deleted > 0;
    }

    /** 标记单条已读；不属于该用户的通知按「不存在」处理，不泄露存在性。 */
    @Transactional
    public void markRead(Long userId, Long id) {
        notificationMapper.markRead(userId, id);
        refreshUnread(userId);
    }

    @Transactional
    public int markReadBatch(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int changed = notificationMapper.markReadBatch(userId, ids);
        refreshUnread(userId);
        return changed;
    }

    @Transactional
    public int markAllRead(Long userId) {
        int changed = notificationMapper.markAllRead(userId);
        refreshUnread(userId);
        return changed;
    }

    // ------------------------------------------------------------------ helpers

    /** 供模块 16 的分类已读使用：改了未读状态后把总数缓存拉回以 DB 为准。 */
    public long refreshUnread(Long userId) {
        return refreshUnreadCount(userId);
    }

    private long refreshUnreadCount(Long userId) {
        long count = notificationMapper.countUnread(userId);
        redisOps.set(RedisKeys.notifyUnread(userId), String.valueOf(count), UNREAD_TTL);
        return count;
    }

    /** PRD §8.2 的 notify.* 映射。未知类型退回系统通知，保证客户端至少能展示标题与正文。 */
    static String wsType(Integer notifyType) {
        if (notifyType == null) {
            return "notify.system";
        }
        switch (notifyType) {
            case Notification.TYPE_COMMENT:
                return "notify.comment";
            case Notification.TYPE_LIKE:
                return "notify.like";
            case Notification.TYPE_FOLLOW:
                return "notify.follow";
            case Notification.TYPE_MESSAGE:
                return "notify.message";
            case Notification.TYPE_MENTION:
                return "notify.mention";
            case Notification.TYPE_ROOM_INVITE:
                return "notify.room_invite";
            case Notification.TYPE_AUDIT:
                return "notify.audit";
            case Notification.TYPE_REPORT:
                return "notify.report";
            case Notification.TYPE_NEW_SONG:
                return "notify.new_song";
            case Notification.TYPE_BAN:
                return "notify.ban";
            case Notification.TYPE_APPEAL:
                return "notify.appeal";
            case Notification.TYPE_RECOMMEND:
                return "notify.recommend";
            case Notification.TYPE_ROOM_FOLLOW:
                return "notify.room_follow";
            case Notification.TYPE_REPOST:
                return "notify.repost";
            case Notification.TYPE_POST_COLLECT:
                return "notify.post_collect";
            default:
                return "notify.system";
        }
    }

    /** 统一消息结构（PRD §8.2 / FR-13-005）。ID 一律字符串。 */
    private Map<String, Object> payload(Notification n) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", String.valueOf(n.getId()));
        data.put("notifyType", n.getNotifyType());
        data.put("title", n.getTitle());
        data.put("content", n.getContent());
        if (n.getFromUserId() != null) {
            Map<String, Object> fromUser = new LinkedHashMap<>();
            fromUser.put("id", String.valueOf(n.getFromUserId()));
            fromUser.put("nickname", n.getFromNickname());
            fromUser.put("avatar", n.getFromAvatar());
            data.put("fromUser", fromUser);
        }
        data.put("targetType", n.getTargetType());
        data.put("targetId", n.getTargetId() == null ? null : String.valueOf(n.getTargetId()));
        data.put("createTime", n.getCreateTime() == null ? System.currentTimeMillis()
                : n.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return data;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * 管理端批量推送。返回成功落库的通知 ID；单个用户失败不中断整批——
     * 运营给 1000 人发通知，不能因为第 7 个用户的外键异常就全量回滚。
     */
    public List<Long> pushBatch(List<Long> userIds, Integer notifyType, String title, String content,
                                Integer targetType, Long targetId) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return userIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(userId -> {
                    Notification n = new Notification();
                    n.setUserId(userId);
                    n.setNotifyType(notifyType == null ? Notification.TYPE_SYSTEM : notifyType);
                    n.setTitle(title);
                    n.setContent(content);
                    n.setTargetType(targetType);
                    n.setTargetId(targetId);
                    n.setIsRead(0);
                    try {
                        return createAndPush(n).getId();
                    } catch (Exception e) {
                        log.error("通知落库失败 userId={} title={} cause={}", userId, title, e.toString());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 供内部排查：某用户的通知总量（不受分页截断影响）。 */
    public long total(Long userId) {
        return notificationMapper.selectCount(new QueryWrapper<Notification>().eq("user_id", userId));
    }
}
