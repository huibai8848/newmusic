package com.example.newmusic.module.ws.support;

import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ws.config.WsProperties;
import com.example.newmusic.module.ws.service.WsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本机 WS 会话表（PRD 13 §5.7 的「本地会话表设计」）。
 *
 * <p>四张索引指向同一批 {@link WsSession}：
 * <ul>
 *   <li>{@code byId}：连接 ID → 会话（关闭、排障、统计）；</li>
 *   <li>{@code byUser}：用户 → 所有设备（多设备推送，BR-13-001）；</li>
 *   <li>{@code byIp}：IP → 会话（握手前的单 IP 上限，BR-13-003）；</li>
 *   <li>{@code byRoom}：房间 → 会话（房间广播，BR-13-040 的「本机订阅者」）。</li>
 * </ul>
 *
 * <p><b>同一 deviceId 只保留最新连接</b>（BR-13-001 / EX-13-001-6）：
 * 注册时先摘掉旧会话再关闭它。顺序不能反——先 close 会触发旧连接的
 * {@code afterConnectionClosed}，那次回调会从 device 索引里删掉「当前设备」这一项，
 * 而它此刻已经指向新连接，等于把新连接从索引里误删。
 *
 * <p>空闲连接扫描放在这里而不是 Handler：扫描对象就是会话表本身，
 * 不需要 Handler 再维护一份「所有连接」。
 */
@Slf4j
@Component
public class WsSessionRegistry {

    private final WsProperties properties;
    private final WsNodeId nodeId;
    private final WsMetrics metrics;
    private final OpsProperties opsProperties;

    private final AtomicLong sequence = new AtomicLong();

    private final Map<String, WsSession> byId = new ConcurrentHashMap<>();
    private final Map<Long, Set<WsSession>> byUser = new ConcurrentHashMap<>();
    private final Map<String, Set<WsSession>> byIp = new ConcurrentHashMap<>();
    private final Map<Long, Set<WsSession>> byRoom = new ConcurrentHashMap<>();
    /** userId → (deviceId → session)。deviceId 只保留最新连接 */
    private final Map<Long, Map<String, WsSession>> byDevice = new ConcurrentHashMap<>();

    public WsSessionRegistry(WsProperties properties, WsNodeId nodeId, WsMetrics metrics,
                             OpsProperties opsProperties) {
        this.properties = properties;
        this.nodeId = nodeId;
        this.metrics = metrics;
        this.opsProperties = opsProperties;
    }

    public String nextConnectionId() {
        return nodeId.connectionId(sequence.incrementAndGet());
    }

    /**
     * 注册新连接。
     *
     * @return 注册成功返回会话；实例连接数已达上限时返回 null（调用方负责关闭新连接）
     */
    public WsSession register(WsSession session) {
        if (byId.size() >= properties.getMaxConnections()) {
            log.warn("WS 连接数已达上限，拒绝新连接 current={} max={}",
                    byId.size(), properties.getMaxConnections());
            metrics.incrementHandshakeFail();
            return null;
        }
        byId.put(session.getConnectionId(), session);
        addToIndex(byUser, session.getUserId(), session);
        addToIndex(byIp, session.getIp(), session);
        metrics.incrementConnectionTotal();

        Map<String, WsSession> devices =
                byDevice.computeIfAbsent(session.getUserId(), k -> new ConcurrentHashMap<>());
        WsSession replaced = devices.put(session.getDeviceId(), session);
        if (replaced != null && replaced != session) {
            // 先摘索引，再关连接：见类注释里「顺序不能反」的说明
            removeFromAllIndexes(replaced);
            log.info("同设备重复连接，关闭旧连接 old={} new={}", replaced.getConnectionId(),
                    session.getConnectionId());
            replaced.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by a newer connection"));
        }
        return session;
    }

    /**
     * 注销连接（幂等）。返回该连接此前所在的房间，便于调用方做房间在线清理。
     */
    public Long unregister(WsSession session) {
        if (session == null) {
            return null;
        }
        Long roomId = session.getRoomId();
        removeFromAllIndexes(session);
        return roomId;
    }

    public WsSession get(String connectionId) {
        return byId.get(connectionId);
    }

    /** 用户在本机的所有设备。 */
    public List<WsSession> userSessions(Long userId) {
        Set<WsSession> sessions = byUser.get(userId);
        return sessions == null ? Collections.<WsSession>emptyList() : new ArrayList<>(sessions);
    }

    /** 房间在本机的订阅者快照。 */
    public List<WsSession> roomSessions(Long roomId) {
        Set<WsSession> sessions = byRoom.get(roomId);
        return sessions == null ? Collections.<WsSession>emptyList() : new ArrayList<>(sessions);
    }

    public boolean hasUserInRoom(Long userId, Long roomId) {
        Set<WsSession> sessions = byRoom.get(roomId);
        if (sessions == null) {
            return false;
        }
        for (WsSession session : sessions) {
            if (userId.equals(session.getUserId())) {
                return true;
            }
        }
        return false;
    }

    public int countByIp(String ip) {
        Set<WsSession> sessions = byIp.get(ip);
        return sessions == null ? 0 : sessions.size();
    }

    public int totalConnections() {
        return byId.size();
    }

    public int totalRooms() {
        return byRoom.size();
    }

    /** 本机各房间的在线人数，用于 stats 与排查。 */
    public Map<String, Integer> roomCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<WsSession>> entry : byRoom.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue().size());
        }
        return result;
    }

    /**
     * 关闭某用户在指定房间里的本机连接（踢人 / 拉黑 / 解散）。
     *
     * <p>只 close、不摘索引：让 {@code afterConnectionClosed} 走统一的清理路径
     * （Redis 在线集合、room.member.leave 广播）。半开连接上 close 可能不回调，
     * 但那类连接会被 {@link #sweepIdle()} 兜底清掉。
     */
    public int closeUserRoomSessions(Long userId, Long roomId, CloseStatus status) {
        if (userId == null || roomId == null) {
            return 0;
        }
        Set<WsSession> sessions = byRoom.get(roomId);
        if (sessions == null) {
            return 0;
        }
        int closed = 0;
        for (WsSession session : new ArrayList<>(sessions)) {
            if (userId.equals(session.getUserId())) {
                session.close(status);
                closed++;
            }
        }
        return closed;
    }

    /** 关闭房间内所有本机连接（关闭/解散房间）。 */
    public int closeRoomSessions(Long roomId, CloseStatus status) {
        if (roomId == null) {
            return 0;
        }
        Set<WsSession> sessions = byRoom.get(roomId);
        if (sessions == null) {
            return 0;
        }
        int closed = 0;
        for (WsSession session : new ArrayList<>(sessions)) {
            session.close(status);
            closed++;
        }
        return closed;
    }

    public void joinRoom(WsSession session, Long roomId) {
        Long previous = session.getRoomId();
        if (previous != null && !previous.equals(roomId)) {
            leaveRoom(session);
        }
        session.setRoomId(roomId);
        addToIndex(byRoom, roomId, session);
    }

    public void leaveRoom(WsSession session) {
        Long roomId = session.getRoomId();
        if (roomId == null) {
            return;
        }
        Set<WsSession> sessions = byRoom.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                byRoom.remove(roomId);
            }
        }
        session.setRoomId(null);
    }

    /**
     * 心跳超时扫描（BR-13-007 / BR-13-009）。
     *
     * <p>只关闭、不直接做 Redis 清理：{@code close()} 会触发
     * {@code afterConnectionClosed}，那里是唯一一处统一清理入口。
     * 但这里也先摘掉本地索引——半开连接上 close 可能迟迟不回调，
     * 而「本地表里还挂着一个死连接」比重复清理危险得多（后者是幂等的）。
     */
    @Scheduled(fixedDelayString = "${newmusic.ws.sweep-interval-ms:10000}")
    public void sweepIdle() {
        long now = System.currentTimeMillis();
        long timeoutMillis = properties.getIdleTimeoutSeconds() * 1000L;
        for (WsSession session : new ArrayList<>(byId.values())) {
            if (now - session.getLastActiveAt() <= timeoutMillis) {
                continue;
            }
            log.info("WS 心跳超时，关闭连接 connectionId={} userId={} idleMs={}",
                    session.getConnectionId(), session.getUserId(), now - session.getLastActiveAt());
            unregister(session);
            session.close(new CloseStatus(4002, "heartbeat timeout"));
        }
    }

    /**
     * 优雅停机（AC-28-011-2）：先广播一帧 {@code server.shutdown} 再关闭。
     *
     * <p>只 close 不通知的话，客户端看到的是 1001 异常断开，只能等重连退避；
     * 先发一帧让前端立刻显示「服务重启中」并主动重连，体验差别很大。
     * 发送后短暂等待，给 TCP 缓冲一点刷出时间。
     */
    @PreDestroy
    public void shutdown() {
        List<WsSession> sessions = new ArrayList<>(byId.values());
        String frame = "{\"type\":\"server.shutdown\",\"message\":\"server restarting\"}";
        for (WsSession session : sessions) {
            try {
                session.send(frame);
            } catch (Exception e) {
                log.debug("停机通知发送失败 connectionId={}", session.getConnectionId());
            }
        }
        long pause = opsProperties == null ? 0L : opsProperties.getWsShutdownPauseMs();
        if (!sessions.isEmpty() && pause > 0) {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (WsSession session : sessions) {
            session.close(new CloseStatus(1001, "server shutting down"));
        }
        byId.clear();
        byUser.clear();
        byIp.clear();
        byRoom.clear();
        byDevice.clear();
    }

    private void removeFromAllIndexes(WsSession session) {
        byId.remove(session.getConnectionId(), session);
        removeFromIndex(byUser, session.getUserId(), session);
        removeFromIndex(byIp, session.getIp(), session);
        Long roomId = session.getRoomId();
        if (roomId != null) {
            removeFromIndex(byRoom, roomId, session);
        }
        Map<String, WsSession> devices = byDevice.get(session.getUserId());
        if (devices != null) {
            // 只在「设备索引仍指向这条会话」时删除，避免误删同设备的新连接
            devices.remove(session.getDeviceId(), session);
            if (devices.isEmpty()) {
                byDevice.remove(session.getUserId(), devices);
            }
        }
    }

    private <K> void addToIndex(Map<K, Set<WsSession>> index, K key, WsSession session) {
        if (key == null) {
            return;
        }
        index.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    private <K> void removeFromIndex(Map<K, Set<WsSession>> index, K key, WsSession session) {
        if (key == null) {
            return;
        }
        Set<WsSession> sessions = index.get(key);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            index.remove(key, sessions);
        }
    }

    /** 给 stats 用：只暴露计数，不吐连接 ID 之外的隐私。 */
    public Collection<WsSession> allSessions() {
        return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
    }
}
