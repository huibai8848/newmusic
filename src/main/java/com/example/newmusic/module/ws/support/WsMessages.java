package com.example.newmusic.module.ws.support;

import cn.hutool.core.util.IdUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WS 消息信封构造（PRD 13 FR-13-008 §8.1）。
 *
 * <pre>
 * { "type": "...", "msgId": "...", "clientMsgId": "...", "roomId": "...", "ts": 0, "data": {...} }
 * </pre>
 *
 * <p>服务端主动下发的业务消息一律带 {@code msgId}——客户端靠它去重（EX-13-007-5）。
 * {@code connected} / {@code pong} / {@code room.chat.ack} 这类控制帧不带：
 * 它们要么是一次性的握手结果，要么已经由 {@code clientMsgId} 关联。
 *
 * <p>ID 统一输出字符串（§8.3：防 JS 精度丢失）。雪花 ID 是 19 位，
 * 若走 Jackson 的 Long 序列化器也会被转成字符串，但这里显式 {@code toString}
 * 让「协议层」不依赖全局序列化配置——WS 消息不经过 Spring MVC 的 ObjectMapper 定制。
 */
public final class WsMessages {

    private WsMessages() {
    }

    /** 控制帧：不带 msgId。 */
    public static Map<String, Object> frame(String type, Object data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        if (data != null) {
            message.put("data", data);
        }
        return message;
    }

    /** 业务帧：带服务端 msgId。 */
    public static Map<String, Object> server(String type, Object data) {
        Map<String, Object> message = frame(type, data);
        message.put("msgId", IdUtil.getSnowflakeNextIdStr());
        return message;
    }

    /**
     * 房间业务帧：在业务帧基础上补顶层 {@code roomId}（§8.1「房间消息必填」）。
     *
     * <p>房间消息的 {@code data} 里也带 roomId（客户端渲染需要），顶层那一个是给
     * 通用分发层用的——客户端可以只读顶层就知道该投到哪个房间的消息列表。
     */
    public static Map<String, Object> serverInRoom(Long roomId, String type, Object data) {
        Map<String, Object> message = server(type, data);
        message.put("roomId", String.valueOf(roomId));
        return message;
    }

    /** 可靠消息回执：原样带回 clientMsgId，便于发送方兑现乐观渲染。 */
    public static Map<String, Object> ack(String type, String clientMsgId, Object data) {
        Map<String, Object> message = frame(type, data);
        if (clientMsgId != null) {
            message.put("clientMsgId", clientMsgId);
        }
        return message;
    }

    /** 错误帧（PRD §8.4）。context 用于让客户端知道「是哪条消息失败了」。 */
    public static Map<String, Object> error(int code, String message, Map<String, Object> context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        if (context != null && !context.isEmpty()) {
            data.put("context", context);
        }
        return frame("error", data);
    }
}
