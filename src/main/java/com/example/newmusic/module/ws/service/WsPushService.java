package com.example.newmusic.module.ws.service;

import com.example.newmusic.module.ws.support.BusEnvelope;
import com.example.newmusic.module.ws.support.WsMessages;
import com.example.newmusic.module.ws.support.WsSession;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 消息下发的唯一入口。
 *
 * <p>三件事收在这里，而不是散在 Handler 与 MQ 订阅者里：
 * <ol>
 *   <li><b>先本机、再跨节点</b>：本机有订阅者就直接写连接；随后发到广播总线，
 *       让用户/房间在两台实例上都有连接时也能都收到（BR-13-001 允许多设备）；</li>
 *   <li><b>去重靠 originNodeId</b>：总线订阅者跳过自己发布的信封，
 *       因此「本机发送」与「总线投递」不会让本机用户收到两遍；</li>
 *   <li><b>序列化只做一次</b>（每个目标连接一次）：广播 50 个连接时，
 *       JSON 只生成一遍。</li>
 * </ol>
 *
 * <p>发送失败只影响该连接，不影响其它订阅者；连接会在下一次清理时移除。
 */
@Slf4j
@Service
public class WsPushService {

    private final WsSessionRegistry registry;
    private final WsClusterBus clusterBus;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;

    public WsPushService(WsSessionRegistry registry, WsClusterBus clusterBus,
                         ObjectMapper objectMapper, WsMetrics metrics) {
        this.registry = registry;
        this.clusterBus = clusterBus;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ 对外 API

    /** 推给一个用户的**全部设备**（本机 + 可能的其它节点）。 */
    public void sendToUser(Long userId, String type, Object data) {
        Map<String, Object> message = WsMessages.server(type, data);
        sendToUserRaw(userId, message);
    }

    /** 房间广播（含发送方本人）。 */
    public void sendToRoom(Long roomId, String type, Object data) {
        sendToRoomRaw(roomId, WsMessages.server(type, data), null);
    }

    /** 房间广播，排除某条连接（成员加入/离开的「通知别人」）。 */
    public void sendToRoomExcept(Long roomId, String type, Object data, String excludeConnectionId) {
        sendToRoomRaw(roomId, WsMessages.server(type, data), excludeConnectionId);
    }

    /** 控制帧下发（connected / pong / room.sync 等）：不带 msgId，不跨节点。 */
    public void sendFrame(WsSession session, String type, Object data) {
        deliver(session, WsMessages.frame(type, data));
    }

    /** 业务帧下发（ack 之外的定向消息，带 msgId）。 */
    public void sendServer(WsSession session, String type, Object data) {
        deliver(session, WsMessages.server(type, data));
    }

    /** 把幂等账本里保存的原始 ack JSON 原样回给客户端（重发场景）。 */
    public void replayRaw(WsSession session, String ackJson) {
        if (ackJson != null && session != null && session.send(ackJson)) {
            metrics.incrementMessagesOut();
        }
    }

    /** 房间业务帧：data 已构造好 msgId，这里负责本机投递 + 跨节点发布。 */
    public void sendRoomMessage(Long roomId, Map<String, Object> message, String excludeConnectionId) {
        deliverToRoom(roomId, message, excludeConnectionId);
        clusterBus.publishRoom(roomId, message, excludeConnectionId);
    }

    /** 可靠消息回执：控制帧，不带 msgId，原样带回 clientMsgId。 */
    public void sendAck(WsSession session, String type, String clientMsgId, Object data) {
        deliver(session, WsMessages.ack(type, clientMsgId, data));
    }

    /** 错误帧（§8.4）。 */
    public void sendError(WsSession session, int code, String message,
                          Map<String, Object> context) {
        deliver(session, WsMessages.error(code, message, context));
    }

    /**
     * 总线订阅者调用：只做本机投递，不再发布——否则同一条消息会在实例之间无限回环。
     */
    public void deliverLocal(BusEnvelope envelope) {
        if (envelope == null || envelope.getMessage() == null) {
            return;
        }
        String targetType = envelope.getTargetType();
        if (BusEnvelope.TARGET_ROOM.equals(targetType)) {
            Long roomId = parseLong(envelope.getTargetId());
            if (roomId == null) {
                return;
            }
            deliverToRoom(roomId, envelope.getMessage(), envelope.getExcludeConnectionId());
        } else if (BusEnvelope.TARGET_USER.equals(targetType)) {
            Long userId = parseLong(envelope.getTargetId());
            if (userId == null) {
                return;
            }
            deliverToUser(userId, envelope.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    private void sendToUserRaw(Long userId, Map<String, Object> message) {
        deliverToUser(userId, message);
        clusterBus.publishUser(userId, message);
    }

    private void sendToRoomRaw(Long roomId, Map<String, Object> message, String excludeConnectionId) {
        deliverToRoom(roomId, message, excludeConnectionId);
        clusterBus.publishRoom(roomId, message, excludeConnectionId);
    }

    private void deliverToUser(Long userId, Map<String, Object> message) {
        List<WsSession> sessions = registry.userSessions(userId);
        for (WsSession session : sessions) {
            deliver(session, message);
        }
    }

    /**
     * 只投本机房间订阅者，不发布到总线。
     *
     * <p>用于「每个节点各自定时下发」的场景（room.sync 兜底）：如果也发布，
     * N 个节点会各发一次、再互相收到 N-1 次，同一房间收到 N² 条重复 sync。
     */
    public void deliverToRoomLocal(Long roomId, Map<String, Object> message) {
        deliverToRoom(roomId, message, null);
    }

    private void deliverToRoom(Long roomId, Map<String, Object> message, String excludeConnectionId) {
        List<WsSession> sessions = registry.roomSessions(roomId);
        for (WsSession session : sessions) {
            if (excludeConnectionId != null
                    && excludeConnectionId.equals(session.getConnectionId())) {
                continue;
            }
            deliver(session, message);
        }
    }

    /** 单连接投递。序列化在这里做，调用方不需要知道 ObjectMapper。 */
    public void deliver(WsSession session, Map<String, Object> message) {
        if (session == null || message == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            if (session.send(payload)) {
                metrics.incrementMessagesOut();
            }
        } catch (Exception e) {
            log.warn("WS 消息序列化失败 type={} cause={}", message.get("type"), e.toString());
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
