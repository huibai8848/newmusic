package com.example.newmusic.module.ws.support;

import com.example.newmusic.module.ws.service.WsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 一条 WS 连接的服务端视图。
 *
 * <p>把 {@link WebSocketSession} 包一层而不是直接在注册表里放原生 session：
 * 连接级的可变状态（所在房间、最后活跃时间、token 到期时间）必须和连接同生共死，
 * 放在注册表的并行 Map 里迟早会出现「session 已关、元数据还在」的错位。
 *
 * <p><b>发送必须串行。</b>{@code WebSocketSession} 不是线程安全的：
 * 房间广播（MQ/Redis 订阅线程）、心跳回执（IO 线程）与业务 ack（处理线程）
 * 可能同时写同一条连接。这里用 session 对象本身做锁，保证一帧写完再写下一帧。
 */
@Slf4j
public class WsSession {

    private final String connectionId;
    private final Long userId;
    private final String nickname;
    private final String deviceId;
    private final String ip;
    private final long tokenExpireAt;
    private final WebSocketSession socket;
    private final WsMetrics metrics;

    private volatile Long roomId;
    private volatile long lastActiveAt;
    private volatile long sentMessages;
    /** auth.expiring 只下发一次：每次心跳都提醒会变成噪声 */
    private volatile boolean authExpiringNotified;

    public WsSession(String connectionId, Long userId, String nickname, String deviceId,
                     String ip, long tokenExpireAt, WebSocketSession socket, WsMetrics metrics) {
        this.connectionId = connectionId;
        this.userId = userId;
        this.nickname = nickname;
        this.deviceId = deviceId;
        this.ip = ip;
        this.tokenExpireAt = tokenExpireAt;
        this.socket = socket;
        this.metrics = metrics;
        this.lastActiveAt = System.currentTimeMillis();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getIp() {
        return ip;
    }

    public long getTokenExpireAt() {
        return tokenExpireAt;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    public long getSentMessages() {
        return sentMessages;
    }

    /** @return true 表示本次是第一次提醒（调用方据此决定是否下发 auth.expiring） */
    public synchronized boolean markAuthExpiringNotified() {
        if (authExpiringNotified) {
            return false;
        }
        authExpiringNotified = true;
        return true;
    }

    public boolean isOpen() {
        return socket.isOpen();
    }

    public WebSocketSession getSocket() {
        return socket;
    }

    /**
     * 写一帧。返回 false 表示连接已不可用（调用方应清理）。
     *
     * <p>发送耗时超过 200ms 记一条慢连接日志（FR-13-009 的 P1 项。
     * 这里只观测、不主动断开——可靠消息的「断开让客户端重连补偿」需要一个
     * 客户端侧的补偿接口，而它随模块 05 一起交付）。
     */
    public boolean send(String payload) {
        if (!socket.isOpen()) {
            return false;
        }
        long started = System.nanoTime();
        try {
            synchronized (socket) {
                socket.sendMessage(new TextMessage(payload));
            }
            sentMessages++;
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (elapsedMs > 200L) {
                if (metrics != null) {
                    metrics.incrementSlow();
                }
                log.warn("慢连接：发送耗时 {}ms connectionId={} userId={}", elapsedMs, connectionId, userId);
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("WS 发送失败，连接将被清理 connectionId={} cause={}", connectionId, e.toString());
            return false;
        }
    }

    public void close(CloseStatus status) {
        try {
            if (socket.isOpen()) {
                socket.close(status);
            }
        } catch (IOException e) {
            log.debug("WS 关闭失败 connectionId={} cause={}", connectionId, e.toString());
        }
    }

    @Override
    public String toString() {
        return "WsSession{" + connectionId + ", userId=" + userId + ", device=" + deviceId
                + ", room=" + roomId + '}';
    }
}
