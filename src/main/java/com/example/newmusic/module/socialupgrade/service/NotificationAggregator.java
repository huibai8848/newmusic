package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.entity.NotificationAggregate;
import com.example.newmusic.module.socialupgrade.mapper.NotificationAggregateMapper;
import com.example.newmusic.module.socialupgrade.support.NotificationCategory;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.mapper.NotificationMapper;
import com.example.newmusic.module.ws.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 通知聚合（FR-25-008）。
 *
 * <p>聚合键与 PRD 一致：{@code type:targetType:targetId:timeBucket}。粉丝关注没有
 * 稳定「对象」，target 固定为 0，否则每个粉丝都会各占一条聚合键，永远聚不起来。
 *
 * <p>Redis 只做快路径标记，DB 的 {@code t_notification_aggregate} 唯一键才是并发下的
 * 事实来源：两个实例同时处理同一聚合键时，后插入者会撞唯一键，随后转入更新分支。
 */
@Slf4j
@Service
public class NotificationAggregator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PARTICIPANTS = 50;

    private final NotificationAggregateMapper aggregateMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationService notificationService;
    private final SocialUserSupport users;
    private final RedisOps redisOps;
    private final SocialUpgradeProperties properties;

    public NotificationAggregator(NotificationAggregateMapper aggregateMapper,
                                  NotificationMapper notificationMapper,
                                  NotificationService notificationService,
                                  SocialUserSupport users, RedisOps redisOps,
                                  SocialUpgradeProperties properties) {
        this.aggregateMapper = aggregateMapper;
        this.notificationMapper = notificationMapper;
        this.notificationService = notificationService;
        this.users = users;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public Notification notify(Notification n) {
        String category = NotificationCategory.of(n.getNotifyType());
        if (!aggregatable(n, category)) {
            return notificationService.createAndPush(n);
        }

        long bucketMillis = bucketMillis(category);
        Duration ttl = Duration.ofMillis(bucketMillis);
        int bucket = bucketIndex(bucketMillis);
        long target = Notification.TYPE_FOLLOW == n.getNotifyType() ? 0
                : (n.getTargetType() == null ? 0 : n.getTargetType());
        long targetId = Notification.TYPE_FOLLOW == n.getNotifyType() ? 0
                : (n.getTargetId() == null ? 0 : n.getTargetId());
        String key = n.getNotifyType() + ":" + target + ":" + targetId + ":" + bucket;

        NotificationAggregate agg = aggregateMapper.selectByKey(n.getUserId(), key);
        if (agg != null && agg.getExpireTime() != null
                && agg.getExpireTime().isAfter(LocalDateTime.now())) {
            return updateExisting(n, agg, key, ttl);
        }

        n.setId(IdWorker.getId());
        NotificationAggregate created = new NotificationAggregate();
        created.setId(IdWorker.getId());
        created.setUserId(n.getUserId());
        created.setAggregateKey(key);
        created.setNotificationId(n.getId());
        created.setParticipantIds(toJson(participantsOf(null, n)));
        created.setCount(1);
        created.setExpireTime(LocalDateTime.now().plus(ttl));
        try {
            aggregateMapper.insert(created);
        } catch (DuplicateKeyException race) {
            NotificationAggregate existing = aggregateMapper.selectByKey(n.getUserId(), key);
            if (existing != null) {
                return updateExisting(n, existing, key, ttl);
            }
        }
        cacheAggregate(n.getUserId(), key, created);
        return notificationService.createAndPush(n);
    }

    private Notification updateExisting(Notification n, NotificationAggregate agg,
                                        String key, Duration ttl) {
        List<Long> ids = parseIds(agg.getParticipantIds());
        List<Long> merged = participantsOf(ids, n);
        int count = (agg.getCount() == null ? 0 : agg.getCount()) + 1;
        agg.setCount(count);
        agg.setParticipantIds(toJson(merged));
        agg.setExpireTime(LocalDateTime.now().plus(ttl));
        aggregateMapper.updateAggregate(agg.getId(), agg.getParticipantIds(), count);

        Notification target = notificationMapper.selectById(agg.getNotificationId());
        if (target == null) {
            // 通知被用户删除后聚合行仍可能残留：直接补一条新通知
            n.setId(IdWorker.getId());
            agg.setNotificationId(n.getId());
            aggregateMapper.updateById(agg);
            cacheAggregate(n.getUserId(), key, agg);
            return notificationService.createAndPush(n);
        }
        target.setContent(aggregatedText(n.getNotifyType(), count, merged));
        target.setFromUserId(n.getFromUserId());
        target.setFromNickname(n.getFromNickname());
        target.setFromAvatar(n.getFromAvatar());
        notificationMapper.updateContent(target.getId(), target.getTitle(), target.getContent(),
                target.getFromUserId(), target.getFromNickname(), target.getFromAvatar());
        target.setIsRead(0);
        cacheAggregate(n.getUserId(), key, agg);
        notificationService.pushExisting(target);
        return target;
    }

    private void cacheAggregate(Long userId, String key, NotificationAggregate agg) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("notificationId", String.valueOf(agg.getNotificationId()));
        hash.put("count", String.valueOf(agg.getCount()));
        hash.put("participants", agg.getParticipantIds() == null ? "[]" : agg.getParticipantIds());
        Duration ttl = Duration.between(LocalDateTime.now(), agg.getExpireTime());
        redisOps.hashSetAll(RedisKeys.notifyAggregate(userId, key), hash);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisOps.expire(RedisKeys.notifyAggregate(userId, key), ttl);
        }
    }

    private String aggregatedText(int type, int count, List<Long> ids) {
        String names = displayNames(ids);
        switch (type) {
            case Notification.TYPE_LIKE:
                return names + (count > 1 ? " 等 " + count + " 人赞了你的动态" : " 赞了你的动态");
            case Notification.TYPE_COMMENT:
                return names + (count > 1 ? " 等 " + count + " 人评论了你的动态" : " 评论了你的动态");
            case Notification.TYPE_REPOST:
                return names + (count > 1 ? " 等 " + count + " 人转发了你的动态" : " 转发了你的动态");
            case Notification.TYPE_POST_COLLECT:
                return names + (count > 1 ? " 等 " + count + " 人收藏了你的动态" : " 收藏了你的动态");
            case Notification.TYPE_MENTION:
                return names + (count > 1 ? " 等 " + count + " 人在动态中提到了你" : " 在动态中提到了你");
            case Notification.TYPE_FOLLOW:
                return names + (count > 1 ? " 等 " + count + " 人关注了你" : " 关注了你");
            default:
                return names;
        }
    }

    private String displayNames(List<Long> ids) {
        List<Long> top = ids.stream().filter(Objects::nonNull).distinct().limit(3)
                .collect(Collectors.toList());
        Map<Long, User> map = users.loadUsers(top);
        List<String> names = new ArrayList<>();
        for (Long id : top) {
            User u = map.get(id);
            names.add(u == null ? "已注销用户" : u.getNickname());
        }
        return names.isEmpty() ? "有人" : String.join("、", names);
    }

    private static List<Long> participantsOf(List<Long> existing, Notification n) {
        List<Long> ids = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (n.getFromUserId() != null && !ids.contains(n.getFromUserId())
                && ids.size() < MAX_PARTICIPANTS) {
            ids.add(n.getFromUserId());
        }
        return ids;
    }

    private static boolean aggregatable(Notification n, String category) {
        if (n.getFromUserId() == null) {
            return false;
        }
        if (NotificationCategory.SYSTEM.equals(category)
                || NotificationCategory.MESSAGE.equals(category)) {
            return false;
        }
        // 音乐提醒里「新歌」在验收里按「每次上架一条」计数（模块 10 verify-12/10），
        // 这里不做聚合，避免第二次上架被合并后计数不增长。
        return n.getNotifyType() != Notification.TYPE_NEW_SONG;
    }

    private long bucketMillis(String category) {
        if (NotificationCategory.INTERACTION.equals(category)) {
            return Duration.ofHours(properties.getNotification().getInteractionBucketHours()).toMillis();
        }
        return Duration.ofHours(properties.getNotification().getRelationBucketHours()).toMillis();
    }

    private static int bucketIndex(long bucketMillis) {
        long now = System.currentTimeMillis();
        return (int) (now / bucketMillis);
    }

    private static String toJson(List<Long> ids) {
        try {
            return JSON.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<Long> parseIds(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
