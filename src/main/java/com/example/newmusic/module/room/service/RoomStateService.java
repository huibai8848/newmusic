package com.example.newmusic.module.room.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.room.vo.QueueItemVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 房间域对 Redis 的唯一读写出口。
 *
 * <p>与 {@code WsPresenceService} 的分工：
 * <ul>
 *   <li>在线集合、播放态、版本号、会话 → {@code WsPresenceService}（模块 04 已交付）；</li>
 *   <li>密码缓存、踢出冷却、麦位/排麦/点歌、最近访问、热度 → 本类（模块 05）。</li>
 * </ul>
 *
 * <p>两处刻意保留的“双写”：
 * <ol>
 *   <li>禁言/黑名单同时写 Redis 与 DB。Redis 是 Handler 每条消息的快速判定来源，
 *       DB 是重启/换实例后的恢复来源；</li>
 *   <li>麦位同时写 {@code t_room_mic} 与 {@code ml:room:mic:{roomId}}。DB 用于成员列表与重启恢复，
 *       Hash 用于实时读取；写入顺序永远先 DB 后 Redis，Redis 故障只会让实时镜像变旧。</li>
 * </ol>
 */
@Slf4j
@Component
public class RoomStateService {

    /** 密码验证通过 30 分钟（BR-14-016） */
    private static final Duration PWD_TTL = Duration.ofMinutes(30);
    /** 密码错误计数窗口 5 分钟（EX-14-002-3） */
    private static final Duration PWD_FAIL_TTL = Duration.ofMinutes(5);
    /** 踢出冷却 5 分钟（BR-14-019） */
    private static final Duration KICK_TTL = Duration.ofMinutes(5);
    /** 互动计数保留 24 小时；热度任务每分钟读一次 */
    private static final Duration INTERACTION_TTL = Duration.ofHours(24);
    /** 最近访问 30 天（PRD 14 §6） */
    private static final Duration RECENT_TTL = Duration.ofDays(30);
    /** 永久禁言用一个足够远的到期时间，避免给 Redis Hash 引入 “-1 特判” 这种隐式契约 */
    private static final long PERMANENT_MUTE_MILLIS = 100L * 365 * 24 * 3600 * 1000;

    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;

    public RoomStateService(RedisOps redisOps, ObjectMapper objectMapper) {
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 房间号

    /**
     * 6 位房间号：Redis 自增，首次从 100000 开始（BR-14-007）。
     *
     * <p>Redis 不可用或号段耗尽时退化为随机 6 位，调用方仍需做唯一性重试；
     * 号段耗尽在生产上意味着 90 万个房间，已经不是本模块的容量问题。
     */
    public long allocateRoomNo() {
        redisOps.setIfAbsent(RedisKeys.ROOM_NO_SEQ, "99999", null);
        long value = redisOps.increment(RedisKeys.ROOM_NO_SEQ, null);
        if (value >= 100000L && value <= 999999L) {
            return value;
        }
        return 100000L + ThreadLocalRandom.current().nextInt(900000);
    }

    // ------------------------------------------------------------------ 密码

    public boolean isPasswordVerified(Long roomId, Long userId) {
        return redisOps.get(RedisKeys.roomPwd(roomId, userId)).isPresent();
    }

    public void markPasswordVerified(Long roomId, Long userId) {
        redisOps.set(RedisKeys.roomPwd(roomId, userId), "1", PWD_TTL);
    }

    public void clearPasswordVerified(Long roomId, Long userId) {
        redisOps.delete(RedisKeys.roomPwd(roomId, userId));
    }

    public long passwordFailCount(Long roomId, Long userId) {
        Optional<String> value = redisOps.get(RedisKeys.roomPwdFail(roomId, userId));
        if (!value.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public long recordPasswordFail(Long roomId, Long userId) {
        return redisOps.increment(RedisKeys.roomPwdFail(roomId, userId), PWD_FAIL_TTL);
    }

    public void clearPasswordFail(Long roomId, Long userId) {
        redisOps.delete(RedisKeys.roomPwdFail(roomId, userId));
    }

    // ------------------------------------------------------------------ 踢出冷却

    public void markKicked(Long roomId, Long userId) {
        redisOps.set(RedisKeys.roomKick(roomId, userId), "1", KICK_TTL);
    }

    public void clearKicked(Long roomId, Long userId) {
        redisOps.delete(RedisKeys.roomKick(roomId, userId));
    }

    public long kickRemainingSeconds(Long roomId, Long userId) {
        long ttl = redisOps.ttlSeconds(RedisKeys.roomKick(roomId, userId));
        return Math.max(ttl, 0L);
    }

    // ------------------------------------------------------------------ 禁言

    public void mute(Long roomId, Long userId, int durationMinutes) {
        long until = durationMinutes < 0
                ? System.currentTimeMillis() + PERMANENT_MUTE_MILLIS
                : System.currentTimeMillis() + durationMinutes * 60_000L;
        Map<String, String> values = new LinkedHashMap<>();
        values.put(String.valueOf(userId), String.valueOf(until));
        redisOps.hashSetAll(RedisKeys.roomMute(roomId), values);
    }

    public void unmute(Long roomId, Long userId) {
        redisOps.hashDelete(RedisKeys.roomMute(roomId), String.valueOf(userId));
    }

    public long muteRemainingSeconds(Long roomId, Long userId) {
        Optional<String> until = redisOps.hashGet(RedisKeys.roomMute(roomId), String.valueOf(userId));
        if (!until.isPresent()) {
            return 0L;
        }
        try {
            long remaining = Long.parseLong(until.get()) - System.currentTimeMillis();
            return remaining > 0 ? (remaining + 999) / 1000 : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 清理该房间中已到期的禁言字段（由定时任务调用）。 */
    public int cleanExpiredMutes(Long roomId) {
        Map<String, String> all = redisOps.hashGetAll(RedisKeys.roomMute(roomId));
        if (all.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, String> entry : all.entrySet()) {
            try {
                if (Long.parseLong(entry.getValue()) <= now) {
                    redisOps.hashDelete(RedisKeys.roomMute(roomId), entry.getKey());
                    removed++;
                }
            } catch (NumberFormatException ignored) {
                redisOps.hashDelete(RedisKeys.roomMute(roomId), entry.getKey());
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ 黑名单

    public boolean isBlacklisted(Long roomId, Long userId) {
        return userId != null && redisOps.isMember(RedisKeys.roomBlacklist(roomId), String.valueOf(userId));
    }

    public void addBlacklist(Long roomId, Long userId) {
        redisOps.addToSet(RedisKeys.roomBlacklist(roomId), String.valueOf(userId), null);
    }

    public void removeBlacklist(Long roomId, Long userId) {
        redisOps.removeFromSet(RedisKeys.roomBlacklist(roomId), String.valueOf(userId));
    }

    public int blacklistSize(Long roomId) {
        return redisOps.members(RedisKeys.roomBlacklist(roomId)).size();
    }

    // ------------------------------------------------------------------ 麦位

    public void occupyMic(Long roomId, int micPos, Long userId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(String.valueOf(micPos), String.valueOf(userId));
        redisOps.hashSetAll(RedisKeys.roomMic(roomId), values);
    }

    public void releaseMic(Long roomId, int micPos) {
        redisOps.hashDelete(RedisKeys.roomMic(roomId), String.valueOf(micPos));
    }

    public Map<String, String> micMap(Long roomId) {
        return redisOps.hashGetAll(RedisKeys.roomMic(roomId));
    }

    // ------------------------------------------------------------------ 排麦队列

    public void enqueueMic(Long roomId, Long userId, long seq) {
        redisOps.zAdd(RedisKeys.roomMicQueue(roomId), String.valueOf(userId), seq, null);
    }

    public void dequeueMic(Long roomId, Long userId) {
        redisOps.zRemove(RedisKeys.roomMicQueue(roomId), String.valueOf(userId));
    }

    public boolean inMicQueue(Long roomId, Long userId) {
        return redisOps.zScore(RedisKeys.roomMicQueue(roomId), String.valueOf(userId)) != null;
    }

    public List<Long> micQueue(Long roomId) {
        List<String> values = redisOps.zRange(RedisKeys.roomMicQueue(roomId), 0, 199);
        List<Long> result = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                result.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过即可
            }
        }
        return result;
    }

    public int micQueueSize(Long roomId) {
        return micQueue(roomId).size();
    }

    // ------------------------------------------------------------------ 播放队列快照

    public void saveQueueSnapshot(Long roomId, List<QueueItemVO> items) {
        try {
            redisOps.set(RedisKeys.roomQueue(roomId),
                    objectMapper.writeValueAsString(items == null ? Collections.emptyList() : items),
                    null);
        } catch (Exception e) {
            log.warn("点歌队列快照写入失败 roomId={} cause={}", roomId, e.toString());
        }
    }

    public List<QueueItemVO> readQueueSnapshot(Long roomId) {
        Optional<String> raw = redisOps.get(RedisKeys.roomQueue(roomId));
        if (!raw.isPresent() || !StringUtils.hasText(raw.get())) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(raw.get(), new TypeReference<List<QueueItemVO>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public long nextQueueSeq(Long roomId) {
        long value = redisOps.increment(RedisKeys.roomQueueSeq(roomId), null);
        if (value > 0) {
            return value;
        }
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ 在线成员快照

    /**
     * 在线成员 ID 快照（只读）。
     *
     * <p>在线集合的**写入方**仍是 {@code WsPresenceService}；这里只提供一次性批量读取，
     * 避免成员列表为每个人各查一次 Redis。
     */
    public java.util.Set<Long> onlineUserIds(Long roomId) {
        java.util.Set<Long> result = new java.util.LinkedHashSet<>();
        for (String value : redisOps.members(RedisKeys.roomOnline(roomId))) {
            try {
                result.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏成员（如游客 g:{deviceId}）跳过；游客不落成员表
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 最近访问 / 热度

    public void addRecentRoom(Long userId, Long roomId) {
        if (userId == null || roomId == null) {
            return;
        }
        // 用 ZADD 覆盖分数而不是 ZINCRBY：最近访问的排序依据是“最后一次访问时间”，
        // 累加时间戳会让老房间的分数越滚越大，最近访问列表直接失真。
        redisOps.zAdd(RedisKeys.roomRecent(userId), String.valueOf(roomId),
                System.currentTimeMillis(), RECENT_TTL);
    }

    public List<Long> recentRoomIds(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Map<String, Double> values = redisOps.zRevRangeWithScores(
                RedisKeys.roomRecent(userId), 0, Math.max(0, limit - 1));
        List<Long> result = new ArrayList<>(values.size());
        for (String key : values.keySet()) {
            try {
                result.add(Long.valueOf(key));
            } catch (NumberFormatException ignored) {
                // 忽略脏成员
            }
        }
        return result;
    }

    public void recordInteraction(Long roomId) {
        if (roomId != null) {
            redisOps.increment(RedisKeys.roomInteraction(roomId), INTERACTION_TTL);
        }
    }

    public long interactionCount(Long roomId) {
        Optional<String> value = redisOps.get(RedisKeys.roomInteraction(roomId));
        if (!value.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void setHotScore(Long roomId, int score) {
        redisOps.set(RedisKeys.roomHot(roomId), String.valueOf(score), Duration.ofMinutes(2));
    }

    public int hotScore(Long roomId) {
        Optional<String> value = redisOps.get(RedisKeys.roomHot(roomId));
        if (!value.isPresent()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.get());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 创建限制（模块 09）

    /**
     * 禁止某用户创建房间。与用户封禁分开：封禁是全局不可用，创建限制只关掉「开房」这一个动作。
     */
    public void banCreate(Long userId, String reason, Duration ttl) {
        redisOps.set(RedisKeys.adminRoomCreateBan(userId),
                StringUtils.hasText(reason) ? reason : "违反房间规范", ttl);
        log.info("后台禁止用户创建房间 userId={} ttl={}", userId, ttl);
    }

    public void unbanCreate(Long userId) {
        redisOps.delete(RedisKeys.adminRoomCreateBan(userId));
    }

    public boolean isCreateBanned(Long userId) {
        if (userId == null || !redisOps.isAvailable()) {
            return false;
        }
        return redisOps.get(RedisKeys.adminRoomCreateBan(userId)).isPresent();
    }

    public String createBanReason(Long userId) {
        if (userId == null || !redisOps.isAvailable()) {
            return null;
        }
        return redisOps.get(RedisKeys.adminRoomCreateBan(userId)).orElse(null);
    }

    // ------------------------------------------------------------------ 投票（模块 13）

    /** 投票元数据保留 2 小时（PRD 22 §8）。 */
    private static final Duration VOTE_TTL = Duration.ofHours(2);
    /** 发起冷却窗口 5 分钟，键多留一倍便于排查（BR-22-038）。 */
    private static final Duration VOTE_COOLDOWN_TTL = Duration.ofMinutes(10);
    /** 榜单/点赞/收听保留 24 小时（BR-22-049）。 */
    private static final Duration RANK_TTL = Duration.ofHours(24);

    public void saveVote(Long roomId, Long songId, Map<String, String> fields) {
        String key = RedisKeys.roomVote(roomId, songId);
        redisOps.hashSetAll(key, fields);
        redisOps.expire(key, VOTE_TTL);
        redisOps.addToSet(RedisKeys.ROOM_VOTE_ACTIVE, voteToken(roomId, songId), VOTE_TTL);
    }

    public Map<String, String> voteMeta(Long roomId, Long songId) {
        return redisOps.hashGetAll(RedisKeys.roomVote(roomId, songId));
    }

    /** @return true 表示这一票是新增的（每人一票，BR-22-036） */
    public boolean addVoter(Long roomId, Long songId, Long userId) {
        return redisOps.addToSetOnce(RedisKeys.roomVoteVoters(roomId, songId),
                String.valueOf(userId), VOTE_TTL);
    }

    public boolean hasVoted(Long roomId, Long songId, Long userId) {
        return userId != null && redisOps.isMember(RedisKeys.roomVoteVoters(roomId, songId),
                String.valueOf(userId));
    }

    public long voterCount(Long roomId, Long songId) {
        return redisOps.setSize(RedisKeys.roomVoteVoters(roomId, songId));
    }

    /** 结束投票：保留元数据（供 GET current 读到结果），从活跃集合移除。 */
    public void finishVote(Long roomId, Long songId, Map<String, String> updates) {
        redisOps.hashSetAll(RedisKeys.roomVote(roomId, songId), updates);
        redisOps.expire(RedisKeys.roomVote(roomId, songId), VOTE_TTL);
        redisOps.removeFromSet(RedisKeys.ROOM_VOTE_ACTIVE, voteToken(roomId, songId));
    }

    /** 活跃投票 token 集合（{@code roomId:songId}），供定时过期任务读取。 */
    public java.util.Set<String> activeVotes() {
        return redisOps.members(RedisKeys.ROOM_VOTE_ACTIVE);
    }

    public void removeActiveVote(String token) {
        redisOps.removeFromSet(RedisKeys.ROOM_VOTE_ACTIVE, token);
    }

    /** 清理某首歌的投票键（投票作废/房间解散）。 */
    public void deleteVote(Long roomId, Long songId) {
        redisOps.delete(RedisKeys.roomVote(roomId, songId));
        redisOps.delete(RedisKeys.roomVoteVoters(roomId, songId));
        redisOps.removeFromSet(RedisKeys.ROOM_VOTE_ACTIVE, voteToken(roomId, songId));
    }

    public java.util.List<String> voteCooldownTimestamps(Long roomId) {
        return redisOps.listRange(RedisKeys.roomVoteCooldown(roomId), 0, 9);
    }

    public void recordVoteStart(Long roomId, long now) {
        String key = RedisKeys.roomVoteCooldown(roomId);
        redisOps.listPush(key, String.valueOf(now), VOTE_COOLDOWN_TTL);
        redisOps.listTrim(key, 0, 9);
    }

    public static String voteToken(Long roomId, Long songId) {
        return roomId + ":" + songId;
    }

    // ------------------------------------------------------------------ 榜单 / 点赞 / 收听（模块 13）

    public double incrRank(Long roomId, String type, Long userId, double delta) {
        return redisOps.zIncrBy(RedisKeys.roomRank(roomId, type), String.valueOf(userId),
                delta, RANK_TTL);
    }

    public java.util.Map<String, Double> topRank(Long roomId, String type, int limit) {
        return redisOps.zRevRangeWithScores(RedisKeys.roomRank(roomId, type), 0,
                Math.max(0, limit - 1));
    }

    public void setRankHidden(Long roomId, Long userId, boolean hidden) {
        String key = RedisKeys.roomRankHidden(roomId);
        if (hidden) {
            redisOps.addToSet(key, String.valueOf(userId), RANK_TTL);
        } else {
            redisOps.removeFromSet(key, String.valueOf(userId));
        }
    }

    public boolean isRankHidden(Long roomId, Long userId) {
        return userId != null && redisOps.isMember(RedisKeys.roomRankHidden(roomId),
                String.valueOf(userId));
    }

    /** 榜单播报节流：1 分钟最多 1 条（BR-22-051）。 */
    public boolean tryRankNotify(Long roomId, String type) {
        return redisOps.setIfAbsent(RedisKeys.roomRankNotify(roomId, type), "1",
                Duration.ofSeconds(60));
    }

    public long incrLike(Long roomId, Long songId) {
        return redisOps.increment(RedisKeys.roomLike(roomId, songId), RANK_TTL);
    }

    public long likeCount(Long roomId, Long songId) {
        Optional<String> value = redisOps.get(RedisKeys.roomLike(roomId, songId));
        if (!value.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** @return true 表示本次是这位用户对这首歌的第一赞 */
    public boolean likeOnce(Long roomId, Long songId, Long userId) {
        return redisOps.addToSetOnce(RedisKeys.roomLikeVoters(roomId, songId),
                String.valueOf(userId), RANK_TTL);
    }

    /** 点赞广播节流：2 秒（PRD 22 §10 ROOM_LIKE_UPDATE）。 */
    public boolean tryLikeNotify(Long roomId) {
        return redisOps.setIfAbsent(RedisKeys.roomLikeNotify(roomId), "1",
                Duration.ofSeconds(2));
    }

    public long lastListenReport(Long roomId, Long userId) {
        Optional<String> value = redisOps.get(RedisKeys.roomListenLast(roomId, userId));
        if (!value.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void markListenReport(Long roomId, Long userId, long now) {
        redisOps.set(RedisKeys.roomListenLast(roomId, userId), String.valueOf(now), RANK_TTL);
    }

    public double addListenSeconds(Long roomId, Long userId, int seconds) {
        return incrRank(roomId, "listen", userId, seconds);
    }

    // ------------------------------------------------------------------ 解散清理

    /**
     * 清理房间域的全部 Redis Key。
     *
     * <p>只删与 roomId 精确相关的键；带 userId 的键按“在线集合里出现过的用户”逐个删，
     * 不做 `ml:room:*` 全库扫描。{@link RedisOps#deleteByPrefix} 本身还会校验 `ml:` 前缀，
     * 因此共享实例上的 {@code ranking:*} 不会被误伤。
     */
    public void clearRoom(Long roomId, List<Long> onlineUserIds) {
        redisOps.delete(RedisKeys.roomOnline(roomId));
        redisOps.delete(RedisKeys.roomOnlineCount(roomId));
        redisOps.delete(RedisKeys.roomPlay(roomId));
        redisOps.delete(RedisKeys.roomVersion(roomId));
        redisOps.delete(RedisKeys.roomMic(roomId));
        redisOps.delete(RedisKeys.roomMicQueue(roomId));
        redisOps.delete(RedisKeys.roomQueue(roomId));
        redisOps.delete(RedisKeys.roomQueueSeq(roomId));
        redisOps.delete(RedisKeys.roomMute(roomId));
        redisOps.delete(RedisKeys.roomBlacklist(roomId));
        redisOps.delete(RedisKeys.roomOwner(roomId));
        redisOps.delete(RedisKeys.roomHot(roomId));
        redisOps.delete(RedisKeys.roomInteraction(roomId));

        // 模块 13：投票 / 榜单 / 点赞 / 收听
        redisOps.delete(RedisKeys.roomVoteCooldown(roomId));
        redisOps.delete(RedisKeys.roomRankHidden(roomId));
        redisOps.delete(RedisKeys.roomLikeNotify(roomId));
        for (String type : new String[]{"gift", "active", "request", "listen"}) {
            redisOps.delete(RedisKeys.roomRank(roomId, type));
            redisOps.delete(RedisKeys.roomRankNotify(roomId, type));
        }
        String votePrefix = RedisKeys.ROOM_VOTE + roomId + ":";
        deleteByRoomPrefix(votePrefix);
        deleteByRoomPrefix(RedisKeys.ROOM_VOTE + "voters:" + roomId + ":");
        deleteByRoomPrefix(RedisKeys.ROOM_LIKE + roomId + ":");
        deleteByRoomPrefix(RedisKeys.ROOM_LISTEN_LAST + roomId + ":");
        // 活跃投票集合里的成员形如 roomId:songId，按前缀逐个移除
        String tokenPrefix = roomId + ":";
        for (String token : activeVotes()) {
            if (token != null && token.startsWith(tokenPrefix)) {
                removeActiveVote(token);
            }
        }

        // 带 userId 的键：踢出冷却 / 密码缓存 / 密码错误计数
        deleteByRoomPrefix(RedisKeys.ROOM_KICK + roomId + ":");
        deleteByRoomPrefix(RedisKeys.ROOM_PWD + roomId + ":");
        deleteByRoomPrefix(RedisKeys.ROOM_PWD_FAIL + roomId + ":");

        if (onlineUserIds != null) {
            for (Long userId : onlineUserIds) {
                if (userId == null) {
                    continue;
                }
                Optional<String> current = redisOps.get(RedisKeys.roomUser(userId));
                if (current.isPresent() && String.valueOf(roomId).equals(current.get())) {
                    redisOps.delete(RedisKeys.roomUser(userId));
                }
            }
        }
    }

    private void deleteByRoomPrefix(String prefix) {
        try {
            redisOps.deleteByPrefix(prefix);
        } catch (IllegalArgumentException e) {
            // 防御：前缀必须落在 ml: 下；这里不可能触发，触发即说明常量被改坏
            log.warn("拒绝清理非 ml: 前缀 {}", prefix);
        }
    }
}
