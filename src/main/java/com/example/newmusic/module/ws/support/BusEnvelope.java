package com.example.newmusic.module.ws.support;

import lombok.Data;

import java.util.Map;

/**
 * 跨实例广播信封（模块 04 D-03）。
 *
 * <p>不是给客户端看的协议，只在实例之间传递。两个字段决定了投递范围：
 * {@code targetType=room} 时 {@code targetId} 是 roomId，{@code targetType=user}
 * 时是 userId。{@code originNodeId} 用于让发布节点跳过自己的消息，避免本地重复推送。
 */
@Data
public class BusEnvelope {

    public static final String TARGET_ROOM = "room";
    public static final String TARGET_USER = "user";

    private String originNodeId;
    private String targetType;
    private String targetId;
    /** 房间广播时可排除某条连接（发送方本人）；跨节点时该连接不存在，会被忽略 */
    private String excludeConnectionId;
    /** 已经构造好的客户端消息信封 */
    private Map<String, Object> message;
}
