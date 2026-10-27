package com.example.newmusic.module.social.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 通知中心（模块 10 FR-19-013）。复用模块 04 的落库/推送实现，只补分类与清空。
 */
@Service
public class NotificationCenterService {

    private final NotificationService notificationService;

    public NotificationCenterService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public PageResult<Notification> page(Long userId, String tab, int pageNum, int pageSize) {
        List<Integer> types = typesOf(tab);
        PageResult<Notification> result = notificationService.page(userId, types, pageNum, pageSize);
        result.getList().forEach(this::decorate);
        return result;
    }

    public long unread(Long userId) {
        return notificationService.unreadCount(userId);
    }

    public void markRead(Long userId, Long id) {
        notificationService.markRead(userId, id);
    }

    public int markReadBatch(Long userId, List<Long> ids) {
        return notificationService.markReadBatch(userId, ids);
    }

    public int markAllRead(Long userId) {
        return notificationService.markAllRead(userId);
    }

    public int clearAll(Long userId) {
        return notificationService.clearAll(userId);
    }

    public boolean delete(Long userId, Long id) {
        return notificationService.deleteOne(userId, id);
    }

    private static List<Integer> typesOf(String tab) {
        if (tab == null) {
            return Collections.emptyList();
        }
        switch (tab) {
            case "interaction":
                return Arrays.asList(Notification.TYPE_LIKE, Notification.TYPE_COMMENT,
                        Notification.TYPE_MENTION, Notification.TYPE_REPOST,
                        Notification.TYPE_POST_COLLECT);
            case "follow":
                return Arrays.asList(Notification.TYPE_FOLLOW, Notification.TYPE_NEW_SONG,
                        Notification.TYPE_ROOM_FOLLOW);
            case "message":
                return Collections.singletonList(Notification.TYPE_MESSAGE);
            case "system":
                return Arrays.asList(Notification.TYPE_SYSTEM, Notification.TYPE_AUDIT,
                        Notification.TYPE_REPORT, Notification.TYPE_BAN,
                        Notification.TYPE_APPEAL, Notification.TYPE_RECOMMEND);
            default:
                return Collections.emptyList();
        }
    }

    private void decorate(Notification n) {
        n.setTargetTypeName(targetTypeName(n.getTargetType()));
    }

    public static String targetTypeName(Integer targetType) {
        if (targetType == null) {
            return null;
        }
        switch (targetType) {
            case Notification.TARGET_SONG:
                return "SONG";
            case Notification.TARGET_PLAYLIST:
                return "PLAYLIST";
            case Notification.TARGET_ALBUM:
                return "ALBUM";
            case Notification.TARGET_COMMENT:
                return "COMMENT";
            case Notification.TARGET_USER:
                return "USER";
            case Notification.TARGET_ROOM:
                return "ROOM";
            case Notification.TARGET_ROOM_MESSAGE:
                return "ROOM_MESSAGE";
            case Notification.TARGET_MESSAGE:
                return "MESSAGE";
            case Notification.TARGET_POST:
                return "POST";
            case Notification.TARGET_TOPIC:
                return "TOPIC";
            default:
                return null;
        }
    }
}
