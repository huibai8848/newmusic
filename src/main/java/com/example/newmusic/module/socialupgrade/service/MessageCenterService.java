package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.social.service.NotificationCenterService;
import com.example.newmusic.module.socialupgrade.support.NotificationCategory;
import com.example.newmusic.module.socialupgrade.vo.UnreadVO;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.mapper.NotificationMapper;
import com.example.newmusic.module.ws.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 升级版消息中心（FR-25-008）：五分类、分类未读、按分类已读。
 *
 * <p>未读数直接 COUNT DB（{@code user_id,is_read} 覆盖索引），不做 Redis 缓存：
 * 通知刚落库就刷新是最常见的操作序列，缓存只会制造「刚收到却显示 0」的错觉。
 */
@Service
public class MessageCenterService {

    private final NotificationService notificationService;
    private final NotificationMapper notificationMapper;
    private final NotificationCenterService centerService;

    public MessageCenterService(NotificationService notificationService,
                                NotificationMapper notificationMapper,
                                NotificationCenterService centerService) {
        this.notificationService = notificationService;
        this.notificationMapper = notificationMapper;
        this.centerService = centerService;
    }

    /** category：interactions/followers/system/music/messages，其它值按全部处理。 */
    public PageResult<Notification> page(Long userId, String category, int pageNum, int pageSize) {
        List<Integer> types = typesOf(category);
        PageResult<Notification> result = notificationService.page(userId, types, pageNum, pageSize);
        result.getList().forEach(this::decorate);
        return result;
    }

    public UnreadVO unread(Long userId) {
        UnreadVO vo = new UnreadVO();
        Map<String, Long> categories = new LinkedHashMap<>();
        long total = 0;
        boolean overflow = false;
        for (String category : NotificationCategory.ALL) {
            List<Integer> types = NotificationCategory.typesOf(category);
            long count = types.isEmpty() ? 0
                    : notificationMapper.countUnreadByTypes(userId, types);
            categories.put(category, count);
            total += count;
            overflow = overflow || count > 99;
        }
        vo.setCategories(categories);
        vo.setTotal(total);
        vo.setOverflow(overflow);
        return vo;
    }

    @Transactional
    public int markRead(Long userId, List<Long> ids) {
        int changed = notificationService.markReadBatch(userId, ids);
        return changed;
    }

    /** category 为空表示全部已读。 */
    @Transactional
    public int markAllRead(Long userId, String category) {
        if (category == null || category.trim().isEmpty()) {
            return notificationService.markAllRead(userId);
        }
        List<Integer> types = typesOf(category);
        if (types.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持的消息分类");
        }
        int changed = notificationMapper.markReadByTypes(userId, types);
        notificationService.refreshUnread(userId);
        return changed;
    }

    public static List<Integer> typesOf(String category) {
        if (category == null || category.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String key = normalize(category);
        if (key == null) {
            return java.util.Collections.emptyList();
        }
        return NotificationCategory.typesOf(key);
    }

    private static String normalize(String category) {
        switch (category.trim().toLowerCase()) {
            case "interaction":
            case "interactions":
                return NotificationCategory.INTERACTION;
            case "follow":
            case "followers":
            case "fans":
                return NotificationCategory.FOLLOW;
            case "message":
            case "messages":
                return NotificationCategory.MESSAGE;
            case "music":
                return NotificationCategory.MUSIC;
            case "system":
                return NotificationCategory.SYSTEM;
            case "all":
                return null;
            default:
                return null;
        }
    }

    private void decorate(Notification n) {
        n.setTargetTypeName(NotificationCenterService.targetTypeName(n.getTargetType()));
    }
}
