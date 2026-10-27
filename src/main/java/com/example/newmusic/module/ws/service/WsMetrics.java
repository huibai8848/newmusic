package com.example.newmusic.module.ws.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 运行指标（PRD 13 FR-13-010 的最小落地）。
 *
 * <p>**没有引 Micrometer 的 Prometheus registry**：依赖白名单里没有
 * {@code micrometer-registry-prometheus}，而只为核心指标引一个 exporter 会让
 * 「装了什么、为什么要装」再次变得不可追溯。这里先保证计数口径正确、
 * 能通过 {@code GET /api/v1/ws/stats} 被断言；接监控时把这几个计数器
 * 注册成 Micrometer {@code Counter/Gauge} 即可，业务代码不用动。
 *
 * <p>指标名与 PRD 一一对应，见 {@link #snapshot(int, int, int)}。
 */
@Component
public class WsMetrics {

    private final AtomicLong connectionsTotal = new AtomicLong();
    private final AtomicLong messagesIn = new AtomicLong();
    private final AtomicLong messagesOut = new AtomicLong();
    private final AtomicLong handshakeFail = new AtomicLong();
    private final AtomicLong slowConnections = new AtomicLong();
    private final AtomicLong broadcastDropped = new AtomicLong();

    public void incrementConnectionTotal() {
        connectionsTotal.incrementAndGet();
    }

    public void incrementMessagesIn() {
        messagesIn.incrementAndGet();
    }

    public void incrementMessagesOut() {
        messagesOut.incrementAndGet();
    }

    public void incrementHandshakeFail() {
        handshakeFail.incrementAndGet();
    }

    public void incrementSlow() {
        slowConnections.incrementAndGet();
    }

    /** 跨实例广播投递失败（Redis 不可用 / 序列化失败），实时性降级为本机。 */
    public void incrementBroadcastDropped() {
        broadcastDropped.incrementAndGet();
    }

    public long getConnectionsTotal() {
        return connectionsTotal.get();
    }

    public long getMessagesIn() {
        return messagesIn.get();
    }

    public long getMessagesOut() {
        return messagesOut.get();
    }

    public long getHandshakeFail() {
        return handshakeFail.get();
    }

    public long getSlowConnections() {
        return slowConnections.get();
    }

    public long getBroadcastDropped() {
        return broadcastDropped.get();
    }

    /**
     * @param currentConnections 由 {@code WsSessionRegistry} 现算，不在这里维护一份副本
     * @param rooms              本机有订阅者的房间数
     * @param pendingExecutor    {@code wsMessageExecutor} 队列积压（落库线程池）
     */
    public Map<String, Object> snapshot(int currentConnections, int rooms, int pendingExecutor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ws_connections_current", currentConnections);
        snapshot.put("ws_connections_total", connectionsTotal.get());
        snapshot.put("ws_messages_in_total", messagesIn.get());
        snapshot.put("ws_messages_out_total", messagesOut.get());
        snapshot.put("ws_handshake_fail_total", handshakeFail.get());
        snapshot.put("ws_slow_connection_total", slowConnections.get());
        snapshot.put("ws_room_broadcast_dropped", broadcastDropped.get());
        snapshot.put("ws_rooms", rooms);
        snapshot.put("ws_message_executor_pending", pendingExecutor);
        return snapshot;
    }
}
