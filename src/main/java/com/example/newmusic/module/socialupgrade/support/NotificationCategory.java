package com.example.newmusic.module.socialupgrade.support;

import com.example.newmusic.module.ws.entity.Notification;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** PRD 16 FR-25-008 的五类消息中心分类。 */
public final class NotificationCategory {

    public static final String INTERACTION = "interaction";
    public static final String FOLLOW = "follow";
    public static final String MESSAGE = "message";
    public static final String SYSTEM = "system";
    public static final String MUSIC = "music";

    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            INTERACTION, FOLLOW, MESSAGE, SYSTEM, MUSIC));

    private NotificationCategory() {
    }

    public static String of(Integer notifyType) {
        if (notifyType == null) {
            return SYSTEM;
        }
        switch (notifyType) {
            case Notification.TYPE_LIKE:
            case Notification.TYPE_COMMENT:
            case Notification.TYPE_MENTION:
            case Notification.TYPE_REPOST:
            case Notification.TYPE_POST_COLLECT:
                return INTERACTION;
            case Notification.TYPE_FOLLOW:
                return FOLLOW;
            case Notification.TYPE_MESSAGE:
                return MESSAGE;
            case Notification.TYPE_NEW_SONG:
            case Notification.TYPE_ROOM_FOLLOW:
                return MUSIC;
            default:
                return SYSTEM;
        }
    }

    public static List<Integer> typesOf(String category) {
        if (INTERACTION.equals(category)) {
            return Arrays.asList(Notification.TYPE_LIKE, Notification.TYPE_COMMENT,
                    Notification.TYPE_MENTION, Notification.TYPE_REPOST,
                    Notification.TYPE_POST_COLLECT);
        }
        if (FOLLOW.equals(category)) {
            return Collections.singletonList(Notification.TYPE_FOLLOW);
        }
        if (MESSAGE.equals(category)) {
            return Collections.singletonList(Notification.TYPE_MESSAGE);
        }
        if (MUSIC.equals(category)) {
            return Arrays.asList(Notification.TYPE_NEW_SONG, Notification.TYPE_ROOM_FOLLOW);
        }
        if (SYSTEM.equals(category)) {
            return Arrays.asList(Notification.TYPE_SYSTEM, Notification.TYPE_ROOM_INVITE,
                    Notification.TYPE_AUDIT, Notification.TYPE_REPORT, Notification.TYPE_BAN,
                    Notification.TYPE_APPEAL, Notification.TYPE_RECOMMEND);
        }
        return Collections.emptyList();
    }
}
