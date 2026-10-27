package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomVote;
import com.example.newmusic.module.room.entity.RoomVoteDetail;
import com.example.newmusic.module.room.mapper.RoomVoteDetailMapper;
import com.example.newmusic.module.room.mapper.RoomVoteMapper;
import com.example.newmusic.module.room.vo.RoomVoteVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.service.WsPresenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投票跳过当前歌曲（FR-22-004）。
 *
 * <p><b>权威划分</b>：Redis 是实时计票来源（一个 Set 去重、一个 Hash 存元数据），
 * DB {@code t_room_vote} 是结果与分析来源。唯一键 {@code uk_vote_room_song} 保证
 * 「同一首歌只能发起一次」，即使 Redis 过期也不会重新放行。
 *
 * <p><b>锁顺序</b>：发起/投票只持 {@code ml:lock:room:vote:{roomId}}；投票通过后再调
 * {@link RoomPlayService#skipCurrent} 取播放锁。播放接口切歌时调用的
 * {@link #cancelForSong} <b>不取投票锁</b>（只做条件更新），否则会形成
 * 「播放锁 → 投票锁 / 投票锁 → 播放锁」的死锁。
 */
@Slf4j
@Service
public class RoomVoteService {

    /** 投票时长 60 秒（BR-22-033，可通过配置覆盖）。 */
    private static final Duration VOTE_DURATION = Duration.ofSeconds(60);
    /** 新歌前 30 秒不可发起投票（BR-22-039）。 */
    private static final long MIN_PLAY_MS = 30_000L;
    /** 剩余不足 10 秒不发起（EX-22-004-7）。 */
    private static final long MIN_REMAIN_MS = 10_000L;
    /** 同房间 5 分钟最多 3 次（BR-22-038）。 */
    private static final long COOLDOWN_WINDOW_MS = 5 * 60_000L;
    private static final int COOLDOWN_LIMIT = 3;
    /** 通过条件二：至少 5 票（BR-22-034）。 */
    private static final int MIN_AGREE = 5;

    private final RoomVoteMapper voteMapper;
    private final RoomVoteDetailMapper detailMapper;
    private final RoomPermissionService permissionService;
    private final RoomStateService stateService;
    private final WsPresenceService presence;
    private final RoomPlayService playService;
    private final RoomNotifier notifier;
    private final DistributedLockService lockService;
    private final UserService userService;
    private final RoomRankService rankService;

    public RoomVoteService(RoomVoteMapper voteMapper, RoomVoteDetailMapper detailMapper,
                           RoomPermissionService permissionService, RoomStateService stateService,
                           WsPresenceService presence, RoomPlayService playService,
                           RoomNotifier notifier, DistributedLockService lockService,
                           UserService userService, RoomRankService rankService) {
        this.voteMapper = voteMapper;
        this.detailMapper = detailMapper;
        this.permissionService = permissionService;
        this.stateService = stateService;
        this.presence = presence;
        this.playService = playService;
        this.notifier = notifier;
        this.lockService = lockService;
        this.userService = userService;
        this.rankService = rankService;
    }

    // ------------------------------------------------------------------ 发起

    @Transactional
    public RoomVoteVO start(Long roomId, Long userId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        return lockService.executeOrFail(RedisKeys.lockRoomVote(roomId),
                () -> startLocked(room, userId));
    }

    private RoomVoteVO startLocked(Room room, Long userId) {
        Long roomId = room.getId();
        Map<String, String> state = presence.playState(roomId);
        Long songId = parseLong(state.get("songId"));
        if (songId == null) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED, "当前没有正在播放的歌曲");
        }
        int playStatus = parseInt(state.get("playStatus"),
                Boolean.parseBoolean(state.getOrDefault("isPlaying", "false")) ? 1 : 2);
        if (playStatus == 4) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED, "歌曲已结束");
        }
        if (playStatus == 2) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED, "当前歌曲已暂停");
        }

        long now = System.currentTimeMillis();
        long expected = RoomPlayService.expectedPositionMs(state, now);
        if (expected < MIN_PLAY_MS) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED,
                    "歌曲刚开始播放，30 秒后才能发起跳过投票");
        }
        Long durationMs = parseLong(state.get("durationMs"));
        if (durationMs == null) {
            Long durationSeconds = parseLong(state.get("duration"));
            durationMs = durationSeconds == null ? 0L : durationSeconds * 1000L;
        }
        if (durationMs > 0 && durationMs - expected < MIN_REMAIN_MS) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED, "歌曲即将结束，无需投票");
        }

        // 同歌只能发起一次：DB 唯一键是最终防线，Redis 只是快路径
        RoomVote exists = voteMapper.selectOne(new QueryWrapper<RoomVote>()
                .eq("room_id", roomId).eq("song_id", songId).last("LIMIT 1"));
        if (exists != null) {
            throw new BizException(ResultCode.ROOM_VOTE_DUPLICATED);
        }
        Map<String, String> meta = stateService.voteMeta(roomId, songId);
        if (!meta.isEmpty() && Integer.valueOf(RoomVote.STATUS_RUNNING).toString()
                .equals(meta.get("status"))) {
            throw new BizException(ResultCode.ROOM_VOTE_DUPLICATED);
        }

        checkCooldown(roomId, now);

        int online = Math.max(presence.onlineCount(roomId), 1);
        long endTime = now + VOTE_DURATION.toMillis();
        RoomVote vote = new RoomVote();
        vote.setRoomId(roomId);
        vote.setSongId(songId);
        vote.setInitiatorId(userId);
        vote.setOnlineCount(online);
        vote.setAgreeCount(0);
        vote.setStatus(RoomVote.STATUS_RUNNING);
        vote.setStartTime(toLocal(now));
        vote.setEndTime(toLocal(endTime));
        voteMapper.insert(vote);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("voteId", String.valueOf(vote.getId()));
        fields.put("roomId", String.valueOf(roomId));
        fields.put("songId", String.valueOf(songId));
        fields.put("songName", nullSafe(state.get("songName")));
        fields.put("initiatorId", String.valueOf(userId));
        fields.put("startTime", String.valueOf(now));
        fields.put("endTime", String.valueOf(endTime));
        fields.put("status", String.valueOf(RoomVote.STATUS_RUNNING));
        fields.put("onlineCount", String.valueOf(online));
        fields.put("agreeCount", "0");
        stateService.saveVote(roomId, songId, fields);
        stateService.recordVoteStart(roomId, now);

        RoomVoteVO vo = toVO(vote, userId);
        broadcast(roomId, "ROOM_VOTE_START", votePayload(vo));
        notifier.roomEvent(roomId, "room.vote.start", votePayload(vo));
        log.info("跳过投票发起 roomId={} songId={} voteId={} online={}",
                roomId, songId, vote.getId(), online);
        return vo;
    }

    private void checkCooldown(Long roomId, long now) {
        int recent = 0;
        long oldest = Long.MAX_VALUE;
        for (String raw : stateService.voteCooldownTimestamps(roomId)) {
            Long ts = parseLong(raw);
            if (ts != null && now - ts < COOLDOWN_WINDOW_MS) {
                recent++;
                oldest = Math.min(oldest, ts);
            }
        }
        if (recent >= COOLDOWN_LIMIT) {
            long retry = Math.max(1L, (oldest + COOLDOWN_WINDOW_MS - now + 999) / 1000L);
            throw new BizException(ResultCode.ROOM_VOTE_COOLDOWN,
                    "投票发起过于频繁，请在 " + retry + " 秒后再试");
        }
    }

    // ------------------------------------------------------------------ 投票

    @Transactional
    public RoomVoteVO agree(Long roomId, Long userId, Long voteId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        return lockService.executeOrFail(RedisKeys.lockRoomVote(roomId),
                () -> agreeLocked(room, userId, voteId));
    }

    private RoomVoteVO agreeLocked(Room room, Long userId, Long voteId) {
        Long roomId = room.getId();
        RoomVote vote = voteId == null ? null : voteMapper.selectById(voteId);
        if (vote == null || !roomId.equals(vote.getRoomId())) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_FOUND);
        }
        if (!Integer.valueOf(RoomVote.STATUS_RUNNING).equals(vote.getStatus())) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_FOUND, "投票已结束");
        }
        long now = System.currentTimeMillis();
        if (vote.getEndTime() != null && toMillis(vote.getEndTime()) <= now) {
            // 不在这里 finish：抛异常会回滚事务，交给 5 秒一次的过期任务收尾
            throw new BizException(ResultCode.ROOM_VOTE_NOT_FOUND, "投票已结束");
        }
        Map<String, String> meta = stateService.voteMeta(roomId, vote.getSongId());
        if (meta.isEmpty()) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_FOUND, "投票已结束");
        }

        boolean added = stateService.addVoter(roomId, vote.getSongId(), userId);
        if (!added) {
            throw new BizException(ResultCode.ROOM_VOTE_ALREADY);
        }
        int agreeCount = (int) stateService.voterCount(roomId, vote.getSongId());
        int online = vote.getOnlineCount() == null ? 1 : Math.max(vote.getOnlineCount(), 1);

        boolean privileged = permissionService.canControl(room, userId);
        boolean passed = privileged
                || agreeCount >= Math.ceil(online * 0.6d)
                || (agreeCount >= MIN_AGREE && agreeCount >= Math.ceil(online * 0.5d));

        vote.setAgreeCount(agreeCount);
        voteMapper.updateById(vote);

        RoomVoteDetail detail = new RoomVoteDetail();
        detail.setVoteId(vote.getId());
        detail.setRoomId(roomId);
        detail.setUserId(userId);
        detail.setAgree(1);
        try {
            detailMapper.insert(detail);
        } catch (Exception e) {
            // 审计表唯一键冲突不影响计票（Redis 才是实时来源）
            log.debug("投票明细重复 voteId={} userId={}", vote.getId(), userId);
        }

        // 活跃榜：投票 × 2（BR-22-047）
        rankService.recordVote(roomId, userId);

        if (passed) {
            finish(vote, RoomVote.STATUS_PASSED, privileged
                    ? "房主/管理员一票通过，已跳过当前歌曲"
                    : "投票通过，已跳过当前歌曲");
            playService.skipCurrent(roomId, userId, true);
        } else {
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("agreeCount", String.valueOf(agreeCount));
            updates.put("status", String.valueOf(RoomVote.STATUS_RUNNING));
            // saveVote 同时续 TTL 并保持活跃集合登记（投票尚未结束）
            stateService.saveVote(roomId, vote.getSongId(), updates);
            Map<String, Object> payload = votePayload(toVO(vote, userId));
            payload.put("agreeCount", agreeCount);
            broadcast(roomId, "ROOM_VOTE_UPDATE", payload);
        }
        return toVO(vote, userId);
    }

    // ------------------------------------------------------------------ 结果 / 取消

    private void finish(RoomVote vote, int status, String message) {
        vote.setStatus(status);
        vote.setEndTime(LocalDateTime.now());
        voteMapper.updateById(vote);

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("status", String.valueOf(status));
        updates.put("agreeCount", String.valueOf(vote.getAgreeCount() == null
                ? 0 : vote.getAgreeCount()));
        updates.put("endTime", String.valueOf(System.currentTimeMillis()));
        stateService.finishVote(vote.getRoomId(), vote.getSongId(), updates);

        boolean passed = status == RoomVote.STATUS_PASSED;
        Map<String, Object> result = votePayload(toVO(vote, null));
        result.put("passed", passed);
        result.put("message", message);
        broadcast(vote.getRoomId(), "ROOM_VOTE_RESULT", result);
        notifier.roomEvent(vote.getRoomId(), "room.vote.result", result);
        if (message != null) {
            notifier.system(vote.getRoomId(), vote.getInitiatorId(), message);
        }
        log.info("跳过投票结束 roomId={} voteId={} status={} agree={}",
                vote.getRoomId(), vote.getId(), status, vote.getAgreeCount());
    }

    /**
     * 切歌时作废进行中的投票（EX-22-004-4）。
     *
     * <p>不取投票锁：调用方已经持有播放锁，再取投票锁会和投票通过路径形成锁环。
     * 条件更新 + Redis 覆盖足够：最坏情况是一票刚好在作废瞬间计入，下一次状态读取会看到取消结果。
     */
    @Transactional
    public void cancelForSong(Long roomId, Long songId, String reason) {
        if (roomId == null || songId == null) {
            return;
        }
        int updated = voteMapper.update(null, new UpdateWrapper<RoomVote>()
                .eq("room_id", roomId)
                .eq("song_id", songId)
                .eq("status", RoomVote.STATUS_RUNNING)
                .set("status", RoomVote.STATUS_CANCELLED)
                .set("end_time", LocalDateTime.now()));
        if (updated == 0) {
            return;
        }
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("status", String.valueOf(RoomVote.STATUS_CANCELLED));
        updates.put("endTime", String.valueOf(System.currentTimeMillis()));
        stateService.finishVote(roomId, songId, updates);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId);
        payload.put("songId", songId);
        payload.put("passed", false);
        payload.put("status", RoomVote.STATUS_CANCELLED);
        payload.put("message", reason);
        broadcast(roomId, "ROOM_VOTE_RESULT", payload);
    }

    /** 定时兜底：60 秒到期未通过 → 未通过（BR-22-033）。 */
    @Scheduled(fixedDelayString = "${newmusic.room.vote-scan-ms:5000}")
    public void expireVotes() {
        for (String token : stateService.activeVotes()) {
            Long roomId = parseLong(token == null || !token.contains(":")
                    ? null : token.substring(0, token.indexOf(':')));
            Long songId = parseLong(token == null || !token.contains(":")
                    ? null : token.substring(token.indexOf(':') + 1));
            if (roomId == null || songId == null) {
                stateService.removeActiveVote(token);
                continue;
            }
            try {
                Map<String, String> meta = stateService.voteMeta(roomId, songId);
                Long endTime = parseLong(meta.get("endTime"));
                if (endTime == null || endTime > System.currentTimeMillis()) {
                    continue;
                }
                Long voteId = parseLong(meta.get("voteId"));
                RoomVote vote = voteId == null ? null : voteMapper.selectById(voteId);
                if (vote != null && Integer.valueOf(RoomVote.STATUS_RUNNING).equals(vote.getStatus())) {
                    finish(vote, RoomVote.STATUS_REJECTED, "投票未通过，当前歌曲继续播放");
                } else {
                    stateService.removeActiveVote(token);
                }
            } catch (Exception e) {
                log.warn("投票过期处理失败 token={} cause={}", token, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    public RoomVoteVO current(Long roomId, Long userId) {
        permissionService.requireRoom(roomId);
        RoomVote vote = voteMapper.selectOne(new QueryWrapper<RoomVote>()
                .eq("room_id", roomId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (vote == null || (vote.getCreateTime() != null
                && vote.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(10)))) {
            return null;
        }
        return toVO(vote, userId);
    }

    // ------------------------------------------------------------------ helpers

    private RoomVoteVO toVO(RoomVote vote, Long viewerId) {
        RoomVoteVO vo = new RoomVoteVO();
        vo.setVoteId(vote.getId());
        vo.setRoomId(vote.getRoomId());
        vo.setSongId(vote.getSongId());
        vo.setInitiatorId(vote.getInitiatorId());
        vo.setOnlineCount(vote.getOnlineCount());
        vo.setAgreeCount(vote.getAgreeCount());
        vo.setStatus(vote.getStatus());
        vo.setPassed(Integer.valueOf(RoomVote.STATUS_PASSED).equals(vote.getStatus()));
        vo.setStartTime(vote.getStartTime() == null ? null : toMillis(vote.getStartTime()));
        vo.setEndTime(vote.getEndTime() == null ? null : toMillis(vote.getEndTime()));
        if (Integer.valueOf(RoomVote.STATUS_RUNNING).equals(vote.getStatus())
                && vo.getEndTime() != null) {
            vo.setRemainingSeconds(Math.max(0L,
                    (vo.getEndTime() - System.currentTimeMillis() + 999) / 1000L));
        } else {
            vo.setRemainingSeconds(0L);
        }
        if (viewerId != null) {
            vo.setVoted(stateService.hasVoted(vote.getRoomId(), vote.getSongId(), viewerId));
        }
        Map<String, String> meta = stateService.voteMeta(vote.getRoomId(), vote.getSongId());
        if (StringUtils.hasText(meta.get("songName"))) {
            vo.setSongName(meta.get("songName"));
        }
        if (vote.getInitiatorId() != null) {
            try {
                User user = userService.getById(vote.getInitiatorId());
                if (user != null) {
                    vo.setInitiatorNickname(user.getNickname());
                }
            } catch (Exception ignored) {
                // 昵称只是展示信息，查不到不影响投票
            }
        }
        return vo;
    }

    private Map<String, Object> votePayload(RoomVoteVO vo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voteId", vo.getVoteId());
        data.put("roomId", vo.getRoomId());
        data.put("songId", vo.getSongId());
        data.put("songName", vo.getSongName());
        data.put("initiatorId", vo.getInitiatorId());
        data.put("initiatorNickname", vo.getInitiatorNickname());
        data.put("onlineCount", vo.getOnlineCount());
        data.put("agreeCount", vo.getAgreeCount());
        data.put("status", vo.getStatus());
        data.put("startTime", vo.getStartTime());
        data.put("endTime", vo.getEndTime());
        data.put("remainingSeconds", vo.getRemainingSeconds());
        data.put("passed", vo.getPassed());
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    private void broadcast(Long roomId, String type, Map<String, Object> data) {
        notifier.roomEvent(roomId, type, data);
    }

    private static LocalDateTime toLocal(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 供验证脚本/测试读取当前进行中的投票 ID。 */
    public List<RoomVote> runningVotes(Long roomId) {
        return voteMapper.selectList(new QueryWrapper<RoomVote>()
                .eq("room_id", roomId).eq("status", RoomVote.STATUS_RUNNING));
    }
}
