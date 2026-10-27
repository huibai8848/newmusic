package com.example.newmusic.module.ws.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ws.config.WsProperties;
import com.example.newmusic.module.ws.support.WsNodeId;
import com.example.newmusic.module.ws.support.WsSession;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WS 相关的 Redis 状态：会话映射、房间在线、权威播放态、房主/禁言/黑名单读取。
 *
 * <p>它是这些键的**唯一读写出口**，Handler 不直接碰 RedisOps。这样降级语义只有一处：
 * 所有读在 Redis 不可用时返回空、所有写在不可用时静默丢弃（{@link RedisOps} 的约定），
 * 本机聊天继续可用，只是失去跨实例能力（PRD §8 降级表）。
 *
 * <p><b>契约归属</b>：房间在线/播放态/版本号的 Key 名与结构来自 PRD 12 章，
 * 模块 05 交付后会成为这些键的正式写入方；本模块先按同一份契约读写，
 * 因此模块 05 接入时不需要迁移数据。唯一的临时物是 {@code ml:room:owner:{roomId}}
 * （见 {@link RedisKeys#ROOM_OWNER}）。
 */
@Slf4j
@Component
public class WsPresenceService {

    /** 播放态与版本号保留 24 小时（BR-22-009：房间结束后自动过期） */
    private static final Duration PLAY_STATE_TTL = Duration.ofHours(24);

    private final RedisOps redisOps;
    private final WsProperties properties;
    private final WsNodeId nodeId;
    private final WsSessionRegistry registry;

    public WsPresenceService(RedisOps redisOps, WsProperties properties, WsNodeId nodeId,
                             WsSessionRegistry registry) {
        this.redisOps = redisOps;
        this.properties = properties;
        this.nodeId = nodeId;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ 会话映射

    /**
     * 写 {@code ml:ws:session:{userId}:{deviceId}} = {@code nodeId:connectionId}。
     *
     * <p>同一 deviceId 的新连接会覆盖旧值——这正是 BR-13-001 要的「只保留最新」。
     */
    public void registerSession(WsSession session) {
        String key = RedisKeys.wsSession(session.getUserId(), session.getDeviceId());
        redisOps.set(key, nodeId.getNodeId() + ':' + session.getConnectionId(), sessionTtl());
    }

    /** 心跳时续期；顺带续房间在线标记（BR-13-011）。 */
    public void refreshPresence(WsSession session) {
        registerSession(session);
        Long roomId = session.getRoomId();
        if (roomId == null) {
            return;
        }
        redisOps.set(RedisKeys.roomUser(session.getUserId()), String.valueOf(roomId), sessionTtl());
        redisOps.addToSet(RedisKeys.roomOnline(roomId), String.valueOf(session.getUserId()), null);
        updateOnlineCount(roomId);
    }

    /**
     * 连接关闭时的清理。
     *
     * <p>两处「只在仍属于自己时才删」：
     * <ul>
     *   <li>会话键：同设备的新连接可能已经覆盖了它，删之前比一次值；</li>
     *   <li>房间在线集合：同用户可能还有别的设备在本房间，只有本机最后一条连接才移除。</li>
     * </ul>
     */
    public void unregisterSession(WsSession session, Long roomId) {
        String key = RedisKeys.wsSession(session.getUserId(), session.getDeviceId());
        String expected = nodeId.getNodeId() + ':' + session.getConnectionId();
        Optional<String> current = redisOps.get(key);
        if (current.isPresent() && expected.equals(current.get())) {
            redisOps.delete(key);
        }
        if (roomId != null) {
            leaveRoom(session.getUserId(), roomId);
        }
    }

    // ------------------------------------------------------------------ 房间在线

    public void joinRoom(WsSession session, Long roomId) {
        redisOps.addToSet(RedisKeys.roomOnline(roomId), String.valueOf(session.getUserId()), null);
        redisOps.set(RedisKeys.roomUser(session.getUserId()), String.valueOf(roomId), sessionTtl());
        updateOnlineCount(roomId);
    }

    public void leaveRoom(Long userId, Long roomId) {
        if (registry.hasUserInRoom(userId, roomId)) {
            // 本机还有该用户的其它连接在房间里：只删本连接的订阅，不动在线集合
            return;
        }
        redisOps.removeFromSet(RedisKeys.roomOnline(roomId), String.valueOf(userId));
        Optional<String> currentRoom = redisOps.get(RedisKeys.roomUser(userId));
        if (currentRoom.isPresent() && String.valueOf(roomId).equals(currentRoom.get())) {
            redisOps.delete(RedisKeys.roomUser(userId));
        }
        updateOnlineCount(roomId);
    }

    /** 在线人数以集合基数为准，不维护第二个计数器——两个真相源迟早会不一致。 */
    public int onlineCount(Long roomId) {
        return redisOps.members(RedisKeys.roomOnline(roomId)).size();
    }

    private void updateOnlineCount(Long roomId) {
        int count = onlineCount(roomId);
        redisOps.set(RedisKeys.roomOnlineCount(roomId), String.valueOf(count), null);
    }

    // ------------------------------------------------------------------ 房主/禁言/黑名单

    /**
     * 演示用的房主占位：首个进房者 SETNX 成功。
     *
     * <p>Redis 不可用时返回当前 userId 而不是 null：房间是本地内存里的概念，
     * 让房主继续控播比「因为缓存挂了所以谁都不能操作」更符合降级原则。
     * 模块 05 会用 {@code t_room.owner_id} 取代这个键（系分 04 D-05）。
     */
    public Long claimOwner(Long roomId, Long userId) {
        String key = RedisKeys.roomOwner(roomId);
        if (!redisOps.isAvailable()) {
            return userId;
        }
        if (redisOps.setIfAbsent(key, String.valueOf(userId), Duration.ofHours(24))) {
            return userId;
        }
        Optional<String> owner = redisOps.get(key);
        if (owner.isPresent()) {
            return parseLong(owner.get(), userId);
        }
        // 竞争窗口里键刚好过期：再抢一次，仍失败就按「别人是房主」处理
        if (redisOps.setIfAbsent(key, String.valueOf(userId), Duration.ofHours(24))) {
            return userId;
        }
        return parseLong(redisOps.get(key).orElse(null), null);
    }

    public Long ownerId(Long roomId) {
        return parseLong(redisOps.get(RedisKeys.roomOwner(roomId)).orElse(null), null);
    }

    public boolean isOwner(Long roomId, Long userId) {
        Long owner = ownerId(roomId);
        return owner != null && owner.equals(userId);
    }

    /** 剩余禁言秒数；未被禁言或 Redis 不可用时返回 0。 */
    public long muteRemainingSeconds(Long roomId, Long userId) {
        Optional<String> until = redisOps.hashGet(RedisKeys.roomMute(roomId), String.valueOf(userId));
        if (!until.isPresent()) {
            return 0L;
        }
        Long untilMillis = parseLong(until.get(), null);
        if (untilMillis == null) {
            return 0L;
        }
        long remaining = untilMillis - System.currentTimeMillis();
        return remaining > 0 ? (remaining + 999) / 1000 : 0L;
    }

    public boolean isBlacklisted(Long roomId, Long userId) {
        return redisOps.isMember(RedisKeys.roomBlacklist(roomId), String.valueOf(userId));
    }

    // ------------------------------------------------------------------ 权威播放态

    /**
     * 写入房间权威播放态并递增版本号（BR-13-020/021）。
     *
     * <p>先 INCR 再写 Hash：版本号与状态分开存，但**版本号是唯一权威**。
     * 客户端只应用 version 更大的消息，因此「Hash 写成功、版本号没写」不会造成
     * 客户端应用旧状态；反过来「版本号涨了、Hash 还是旧的」会让客户端对齐到旧进度，
     * 但下一次 sync 会修正。这个顺序是两者中代价更小的。
     *
     * @return 新版本号；Redis 不可用时为 0（此时房间功能降级，见 PRD §8）
     */
    public long savePlayState(Long roomId, Map<String, String> fields) {
        long version = redisOps.increment(RedisKeys.roomVersion(roomId), PLAY_STATE_TTL);
        fields.put("version", String.valueOf(version));
        fields.put("serverTime", String.valueOf(System.currentTimeMillis()));
        // Redis Hash 不能存 null：Redisson 编码器会在 toString() 上抛 NPE，
        // 表现为「版本号涨了、状态没写进去」。封面/歌手等可选字段必须落成空串。
        fields.replaceAll((key, value) -> value == null ? "" : value);
        redisOps.hashSetAll(RedisKeys.roomPlay(roomId), fields);
        // HSET 不会带过期时间；每写一次都续到 24h，房间闲置后自动消失（BR-22-009）。
        // 版本号键也可能是模块 04 时期创建的（无 TTL），一并补上。
        redisOps.expire(RedisKeys.roomPlay(roomId), PLAY_STATE_TTL);
        redisOps.expire(RedisKeys.roomVersion(roomId), PLAY_STATE_TTL);
        return version;
    }

    /** 权威播放态。不存在时返回空 Map（而不是 null，调用方不必再判空）。 */
    public Map<String, String> playState(Long roomId) {
        return redisOps.hashGetAll(RedisKeys.roomPlay(roomId));
    }

    public long version(Long roomId) {
        Optional<String> value = redisOps.get(RedisKeys.roomVersion(roomId));
        if (value.isPresent()) {
            Long parsed = parseLong(value.get(), 0L);
            return parsed == null ? 0L : parsed;
        }
        Long fromHash = parseLong(playState(roomId).get("version"), 0L);
        return fromHash == null ? 0L : fromHash;
    }

    /**
     * 拼一份可直接下发的 room.sync / room.join.ack 快照。
     *
     * <p>同时带两套字段：{@code position/isPlaying} 给模块 04 的老客户端，
     * {@code playStatus/startPositionMs/playStateVersion} 给模块 13 的新客户端。
     */
    public Map<String, Object> playSnapshot(Long roomId) {
        Map<String, String> state = playState(roomId);
        long version = version(roomId);
        Long duration = parseLong(state.get("duration"), null);
        long position = parseLong(state.get("position"), 0L);
        boolean playing = Boolean.parseBoolean(state.getOrDefault("isPlaying", "false"));
        long startPositionMs = parseLong(state.get("startPositionMs"), position * 1000L);
        long stateUpdateTimeMs = parseLong(state.get("stateUpdateTimeMs"),
                parseLong(state.get("serverTime"), System.currentTimeMillis()));
        long durationMs = parseLong(state.get("durationMs"),
                duration == null ? 0L : duration * 1000L);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("roomId", String.valueOf(roomId));
        snapshot.put("songId", state.get("songId"));
        snapshot.put("songName", state.get("songName"));
        snapshot.put("artistName", state.get("artistName"));
        snapshot.put("cover", state.get("cover"));
        snapshot.put("duration", duration);
        snapshot.put("durationMs", durationMs);
        snapshot.put("position", position);
        snapshot.put("isPlaying", playing);
        snapshot.put("playStatus", parseInt(state.get("playStatus"), playing ? 1 : 2));
        snapshot.put("startPositionMs", startPositionMs);
        snapshot.put("stateUpdateTimeMs", stateUpdateTimeMs);
        snapshot.put("playbackRate", parseInt(state.get("playbackRate"), 100));
        snapshot.put("volume", parseInt(state.get("volume"), 100));
        snapshot.put("updatedBy", state.get("updatedBy") != null
                ? state.get("updatedBy") : state.get("operatorId"));
        snapshot.put("operatorId", state.get("operatorId"));
        snapshot.put("version", version);
        snapshot.put("playStateVersion", version);
        snapshot.put("serverTime", parseLong(state.get("serverTime"), System.currentTimeMillis()));
        return snapshot;
    }

    // ------------------------------------------------------------------ 节点负载

    @PostConstruct
    public void registerNode() {
        refreshNode();
    }

    @Scheduled(fixedDelayString = "${newmusic.ws.node-refresh-ms:30000}")
    public void refreshNode() {
        redisOps.set(RedisKeys.wsNode(nodeId.getNodeId()),
                String.valueOf(registry.totalConnections()),
                Duration.ofSeconds(properties.getNodeTtlSeconds()));
    }

    @PreDestroy
    public void unregisterNode() {
        redisOps.delete(RedisKeys.wsNode(nodeId.getNodeId()));
    }

    // ------------------------------------------------------------------ helpers

    private Duration sessionTtl() {
        return Duration.ofSeconds(properties.getSessionTtlSeconds());
    }

    private static Long parseLong(String value, Long fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
