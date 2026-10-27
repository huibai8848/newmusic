package com.example.newmusic.module.ws.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模块 04 的 WebSocket 配置（{@code newmusic.ws.*}）。
 *
 * <p>所有阈值都做成配置而不是常量：容量类参数（连接上限、单 IP 上限）随部署形态变化，
 * 心跳/幂等窗口则是联调时最常被临时调小的两处。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.ws")
public class WsProperties {

    /** 节点标识；留空则启动时随机生成（见 {@code WsNodeId}） */
    private String nodeId = "";

    /** Origin 白名单，逗号分隔；{@code *} 仅本机演示可用 */
    private String allowedOrigins = "*";

    /** 心跳间隔（秒），在 connected 帧里下发给客户端 */
    private int heartbeatIntervalSeconds = 30;

    /** 服务端空闲超时（秒）：3 个心跳周期 */
    private int idleTimeoutSeconds = 90;

    /** 单实例连接上限 */
    private int maxConnections = 10000;

    /** 单 IP 连接上限 */
    private int maxConnectionsPerIp = 20;

    /** 单条消息字节上限 */
    private int maxMessageSize = 65536;

    /** 聊天频率上限（条/分钟） */
    private int chatRatePerMinute = 20;

    /** 弹幕频率上限（条/分钟） */
    private int danmakuRatePerMinute = 10;

    /** clientMsgId 幂等窗口（秒） */
    private int dedupTtlSeconds = 300;

    /** 用户会话映射 TTL（秒），心跳续期 */
    private int sessionTtlSeconds = 90;

    /** 节点负载键 TTL（秒） */
    private int nodeTtlSeconds = 60;

    /** token 剩余有效期低于该值时下发 auth.expiring */
    private int authExpiringSeconds = 300;

    /** 房间兜底 room.sync 周期（毫秒）；@Scheduled 直接用它，避免秒/毫秒换算散落两处 */
    private long roomSyncIntervalMs = 60000L;

    /**
     * 演示开关：模块 05 未交付时的入房/房主策略。
     *
     * <p>为 true 时「首个进房者成为房主」，任意登录用户可加入任意数字 roomId；
     * 为 false 时 room.join 直接 fail-closed（40001）。生产必须为 false。
     */
    private boolean demoRoomEnabled = true;

    /** 解析为 Spring 的 allowed origin patterns。 */
    public String[] originPatterns() {
        if (allowedOrigins == null || allowedOrigins.trim().isEmpty()) {
            return new String[]{"*"};
        }
        List<String> values = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        return values.isEmpty() ? new String[]{"*"} : values.toArray(new String[0]);
    }

    /** 只读视图，便于断言/排查。 */
    public List<String> originList() {
        String[] patterns = originPatterns();
        return patterns == null ? Collections.<String>emptyList() : Arrays.asList(patterns);
    }
}
