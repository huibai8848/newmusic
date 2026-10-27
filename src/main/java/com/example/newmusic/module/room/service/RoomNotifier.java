package com.example.newmusic.module.room.service;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.service.NotificationService;
import com.example.newmusic.module.ws.service.RoomMessageService;
import com.example.newmusic.module.ws.service.WsPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间事件下发与系统消息落库。
 *
 * <p>为什么单独一层：模块 05 的每个写操作几乎都要做同样的三件事——
 * 广播一个 `room.*` 事件、写一条 `t_room_message(type=3)`、必要时发站内通知。
 * 写在各 Service 里会出现“有的操作广播了没落库、有的落了库没广播”的不一致。
 */
@Slf4j
@Component
public class RoomNotifier {

    /** 通知跳转目标类型：房间 */
    public static final int TARGET_ROOM = 6;

    private final WsPushService pushService;
    private final RoomMessageService roomMessageService;
    private final NotificationService notificationService;

    public RoomNotifier(WsPushService pushService, RoomMessageService roomMessageService,
                        NotificationService notificationService) {
        this.pushService = pushService;
        this.roomMessageService = roomMessageService;
        this.notificationService = notificationService;
    }

    /** 房间业务事件（全房间，含触发者）。 */
    public void roomEvent(Long roomId, String type, Object data) {
        try {
            pushService.sendToRoom(roomId, type, data);
        } catch (Exception e) {
            log.warn("房间事件下发失败 roomId={} type={} cause={}", roomId, type, e.toString());
        }
    }

    /** 定向事件（被禁言/被踢/点歌结果）。 */
    public void userEvent(Long userId, String type, Object data) {
        try {
            pushService.sendToUser(userId, type, data);
        } catch (Exception e) {
            log.warn("房间定向事件下发失败 userId={} type={} cause={}", userId, type, e.toString());
        }
    }

    /**
     * 系统消息：先写库（与广播共用同一个 messageId），再广播。
     *
     * <p>顺序与模块 04 的通知一致：先库后推，客户端断线补偿时能拉到同一条。
     */
    public void system(Long roomId, Long operatorId, String content) {
        if (roomId == null || content == null || content.isEmpty()) {
            return;
        }
        long messageId = IdUtil.getSnowflakeNextId();
        String text = content.length() > 1000 ? content.substring(0, 1000) : content;
        roomMessageService.append(messageId, roomId, operatorId == null ? 0L : operatorId,
                RoomMessage.TYPE_SYSTEM, text, null, 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", String.valueOf(messageId));
        data.put("roomId", String.valueOf(roomId));
        data.put("userId", operatorId == null ? "0" : String.valueOf(operatorId));
        data.put("content", text);
        data.put("messageType", RoomMessage.TYPE_SYSTEM);
        data.put("createTime", System.currentTimeMillis());
        roomEvent(roomId, "room.system", data);
    }

    /** 站内通知 + 实时 `notify.system`。 */
    public void notifyUser(Long userId, Long fromUserId, String fromNickname,
                           String title, String content, Room room) {
        if (userId == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setNotifyType(Notification.TYPE_SYSTEM);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setFromUserId(fromUserId);
        notification.setFromNickname(fromNickname);
        notification.setTargetType(TARGET_ROOM);
        notification.setTargetId(room == null ? null : room.getId());
        notification.setIsRead(0);
        try {
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("房间站内通知失败 userId={} title={} cause={}", userId, title, e.toString());
        }
    }

    public void notifyBatch(List<Long> userIds, String title, String content, Room room) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            notificationService.pushBatch(userIds, Notification.TYPE_SYSTEM, title, content,
                    TARGET_ROOM, room == null ? null : room.getId());
        } catch (Exception e) {
            log.warn("房间批量通知失败 roomId={} cause={}",
                    room == null ? null : room.getId(), e.toString());
        }
    }
}
