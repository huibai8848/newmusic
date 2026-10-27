package com.example.newmusic.module.room.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.room.dto.PlayControlReq;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomQueue;
import com.example.newmusic.module.room.vo.RoomPlayStateVO;
import com.example.newmusic.module.ws.service.WsPresenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 房间权威播放态（FR-22-001 / FR-22-002 的服务端部分）。
 *
 * <p><b>唯一写入口</b>：HTTP {@code POST /rooms/{id}/play} 与 WebSocket 的
 * {@code room.play/pause/seek/switch} 都走 {@link #applyLocked}。客户端永远只是「请求」，
 * 状态由服务端计算、落 Redis、广播，客户端只应用版本号更大的消息（BR-22-001/002）。
 *
 * <p><b>双字段兼容</b>：Redis Hash 与广播帧里同时写毫秒制新字段
 * （{@code playStatus/startPositionMs/playStateVersion}）和模块 04 的秒制旧字段
 * （{@code position/isPlaying}）。模块 04/05 的验收脚本与老客户端因此不需要改动。
 *
 * <p>校准（软/硬）完全在客户端本地完成（PRD §11：服务端不推送校准消息），
 * 服务端只保证「同一份权威状态 + 服务端时间戳」。
 */
@Slf4j
@Service
public class RoomPlayService {

    public static final String ACTION_PLAY = "play";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_SEEK = "seek";
    public static final String ACTION_SWITCH = "switch";
    public static final String ACTION_BUFFERING = "buffering";
    public static final String ACTION_END = "end";

    private final WsPresenceService presence;
    private final RoomPermissionService permissionService;
    private final RoomService roomService;
    private final RoomQueueService queueService;
    private final SongService songService;
    private final RoomNotifier notifier;
    private final DistributedLockService lockService;
    /** 点赞数读取（PRD 22 BR-22-069：进房就要看到累计点赞数） */
    private final RoomStateService stateService;
    /** 延迟注入：投票通过后要调本服务切歌，本服务切歌时又要作废进行中的投票 */
    private final ObjectProvider<RoomVoteService> voteServiceProvider;

    public RoomPlayService(WsPresenceService presence, RoomPermissionService permissionService,
                           RoomService roomService, RoomQueueService queueService,
                           SongService songService, RoomNotifier notifier,
                           DistributedLockService lockService, RoomStateService stateService,
                           ObjectProvider<RoomVoteService> voteServiceProvider) {
        this.presence = presence;
        this.permissionService = permissionService;
        this.roomService = roomService;
        this.queueService = queueService;
        this.songService = songService;
        this.notifier = notifier;
        this.lockService = lockService;
        this.stateService = stateService;
        this.voteServiceProvider = voteServiceProvider;
    }

    // ------------------------------------------------------------------ 查询

    public RoomPlayStateVO current(Long roomId) {
        permissionService.requireRoom(roomId);
        return toVO(roomId, presence.playSnapshot(roomId));
    }

    public Map<String, Object> snapshot(Long roomId) {
        return presence.playSnapshot(roomId);
    }

    // ------------------------------------------------------------------ 控制

    /** HTTP 控播：权限与串行化都在这里。 */
    public RoomPlayStateVO control(Long roomId, Long userId, PlayControlReq req) {
        Room room = permissionService.requireActiveForOperation(roomId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        if (!permissionService.canControlPlayback(room, userId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
        String action = normalizeAction(req.getAction());
        Map<String, Object> snapshot = lockService.executeOrFail(RedisKeys.lockRoomPlay(roomId),
                () -> applyLocked(roomId, userId, action, req.getSongId(),
                        req.getPositionMs(), req.getVolume(), null, true));
        return toVO(roomId, snapshot);
    }

    /**
     * WebSocket 控播。调用方已完成权限校验与幂等去重。
     *
     * @param legacyType 模块 04 的消息类型（{@code room.play} 等）；广播时额外发一帧旧协议
     * @return 最新快照（调用方用于 ack）
     */
    public Map<String, Object> applyWsControl(Long roomId, Long userId, String legacyType,
                                              String action, Long songId, Long positionMs,
                                              Integer volume) {
        String normalized = normalizeAction(action);
        return lockService.executeOrFail(RedisKeys.lockRoomPlay(roomId),
                () -> applyLocked(roomId, userId, normalized, songId, positionMs, volume,
                        legacyType, true));
    }

    /**
     * 跳过当前歌曲（投票通过 / 房主直接跳过）。
     *
     * <p>当前队列项落为 {@link RoomQueue#STATUS_SKIPPED}，然后播下一首已通过项；
     * 队列为空时停在「已结束」，不清空当前歌曲信息，便于客户端展示封面与歌名。
     */
    public Map<String, Object> skipCurrent(Long roomId, Long userId, boolean byVote) {
        permissionService.requireActiveForOperation(roomId);
        return lockService.executeOrFail(RedisKeys.lockRoomPlay(roomId), () -> {
            Map<String, String> state = presence.playState(roomId);
            Long previousSongId = parseLong(state.get("songId"));
            if (previousSongId == null) {
                return presence.playSnapshot(roomId);
            }
            queueService.markSkipped(roomId, previousSongId);
            RoomQueue next = queueService.nextApproved(roomId);

            Map<String, String> fields;
            if (next != null) {
                fields = baseFields(state);
                putSong(fields, next.getSongId(), next.getSongName(), next.getArtistName(),
                        next.getCover(), next.getDuration());
                fields.put("playStatus", "1");
                fields.put("startPositionMs", "0");
                fields.put("position", "0");
                fields.put("isPlaying", "true");
            } else {
                fields = baseFields(state);
                long durationMs = parseLong(state.get("durationMs"),
                        parseLong(state.get("duration"), 0L) == null ? 0L
                                : parseLong(state.get("duration"), 0L) * 1000L);
                fields.put("playStatus", "4");
                fields.put("startPositionMs", String.valueOf(durationMs));
                fields.put("position", String.valueOf(durationMs / 1000L));
                fields.put("isPlaying", "false");
            }
            fields.put("stateUpdateTimeMs", String.valueOf(System.currentTimeMillis()));
            fields.put("playbackRate", "100");
            fields.put("updatedBy", String.valueOf(userId));
            fields.put("operatorId", String.valueOf(userId));
            // 跳过动作不再触发「作废投票」：调用方通常正在处理该投票
            long version = presence.savePlayState(roomId, fields);
            Map<String, Object> snapshot = presence.playSnapshot(roomId);
            snapshot.put("version", version);

            Long newSongId = next == null ? null : next.getSongId();
            roomService.onPlayStateChanged(roomId, previousSongId, newSongId);
            broadcast(roomId, snapshot, byVote ? "room.vote.skip" : "room.switch");
            log.info("房间跳过当前歌曲 roomId={} songId={} byVote={} next={}",
                    roomId, previousSongId, byVote, newSongId);
            return snapshot;
        });
    }

    // ------------------------------------------------------------------ 核心状态机

    /**
     * 计算并写入新状态。调用方必须已持有 {@code ml:lock:room:play:{roomId}}。
     */
    private Map<String, Object> applyLocked(Long roomId, Long userId, String action, Long reqSongId,
                                            Long reqPositionMs, Integer reqVolume,
                                            String legacyType, boolean cancelVotes) {
        Map<String, String> state = presence.playState(roomId);
        long now = System.currentTimeMillis();
        Long currentSongId = parseLong(state.get("songId"));

        Song song = reqSongId == null ? null : songService.requirePlayable(reqSongId);
        Long songId = song != null ? song.getId() : currentSongId;
        if (songId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "房间当前没有正在播放的歌曲，请先切歌");
        }
        // 首次直接切歌（Redis 里还没有 songId）也要同步 DB 当前歌曲
        boolean songChanged = !java.util.Objects.equals(currentSongId, songId);

        long expected = expectedPositionMs(state, now);
        int currentStatus = parseInt(state.get("playStatus"),
                Boolean.parseBoolean(state.getOrDefault("isPlaying", "false")) ? 1 : 2);
        long durationMs = parseLong(state.get("durationMs"),
                parseLong(state.get("duration"), 0L) == null ? 0L
                        : parseLong(state.get("duration"), 0L) * 1000L);
        long currentStart = parseLong(state.get("startPositionMs"),
                parseLong(state.get("position"), 0L) * 1000L);

        int status;
        long start;
        switch (action) {
            case ACTION_PLAY:
                status = 1;
                start = reqPositionMs != null ? nonNegative(reqPositionMs)
                        : (songChanged ? 0L
                        : (currentStatus == 2 || currentStatus == 4 ? currentStart : expected));
                break;
            case ACTION_PAUSE:
                status = 2;
                start = reqPositionMs != null ? nonNegative(reqPositionMs) : expected;
                break;
            case ACTION_SEEK:
                // 暂停时拖动仍保持暂停；播放中/缓冲区拖动后继续播
                status = currentStatus == 2 ? 2 : 1;
                start = reqPositionMs != null ? nonNegative(reqPositionMs) : expected;
                break;
            case ACTION_SWITCH:
                status = 1;
                start = reqPositionMs != null ? nonNegative(reqPositionMs) : 0L;
                break;
            case ACTION_BUFFERING:
                status = 3;
                start = reqPositionMs != null ? nonNegative(reqPositionMs) : expected;
                break;
            case ACTION_END:
                status = 4;
                start = reqPositionMs != null ? nonNegative(reqPositionMs) : durationMs;
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的播放动作：" + action);
        }

        Map<String, String> fields = baseFields(state);
        if (song != null) {
            putSong(fields, song.getId(), song.getName(), song.getArtistName(), song.getCover(),
                    song.getDuration());
            durationMs = song.getDuration() == null ? 0L : song.getDuration() * 1000L;
        } else {
            fields.put("songId", String.valueOf(songId));
            fields.put("songName", state.get("songName"));
            fields.put("artistName", state.get("artistName"));
            fields.put("cover", state.get("cover"));
            fields.put("duration", state.getOrDefault("duration", "0"));
            fields.put("durationMs", String.valueOf(durationMs));
        }
        if (durationMs > 0 && start > durationMs) {
            start = durationMs;
        }
        int volume = reqVolume != null ? clampVolume(reqVolume)
                : parseInt(state.get("volume"), 100);

        fields.put("playStatus", String.valueOf(status));
        fields.put("startPositionMs", String.valueOf(start));
        fields.put("stateUpdateTimeMs", String.valueOf(now));
        fields.put("playbackRate", "100");
        fields.put("volume", String.valueOf(volume));
        fields.put("updatedBy", String.valueOf(userId));
        fields.put("operatorId", String.valueOf(userId));
        // 模块 04 兼容字段
        fields.put("position", String.valueOf(start / 1000L));
        fields.put("isPlaying", String.valueOf(status == 1));

        long version = presence.savePlayState(roomId, fields);
        Map<String, Object> snapshot = presence.playSnapshot(roomId);
        snapshot.put("version", version);

        if (songChanged) {
            roomService.onPlayStateChanged(roomId, currentSongId, songId);
            if (cancelVotes) {
                cancelVotesOf(roomId, currentSongId);
            }
        }
        broadcast(roomId, snapshot, legacyType);
        return snapshot;
    }

    // ------------------------------------------------------------------ 广播

    private void broadcast(Long roomId, Map<String, Object> snapshot, String legacyType) {
        // 点赞数是累计值，随播放态一起下发；只靠 ROOM_LIKE_UPDATE 会让后进房的人看到 0
        Long songId = asLong(snapshot.get("songId"));
        if (songId != null) {
            snapshot.put("likeCount", stateService.likeCount(roomId, songId));
        }
        // 新协议：权威播放态
        notifier.roomEvent(roomId, "ROOM_PLAY_STATE", snapshot);
        // 旧协议：room.play / room.pause / ...（模块 04 客户端只认这些）
        if (StringUtils.hasText(legacyType)) {
            notifier.roomEvent(roomId, legacyType, snapshot);
        }
    }

    private void cancelVotesOf(Long roomId, Long songId) {
        try {
            RoomVoteService voteService = voteServiceProvider.getIfAvailable();
            if (voteService != null && songId != null) {
                voteService.cancelForSong(roomId, songId, "歌曲已切换，投票自动取消");
            }
        } catch (Exception e) {
            log.warn("作废切歌投票失败 roomId={} songId={} cause={}", roomId, songId, e.toString());
        }
    }

    // ------------------------------------------------------------------ 纯函数

    /**
     * 客户端「现在应该播放到哪里」的同一套算法（PRD FR-22-001）。
     * 服务端用它计算暂停/拖动的基准位置；前端 {@code roomSync.ts} 用它做校准。
     */
    public static long expectedPositionMs(Map<String, String> state, long now) {
        int status = parseInt(state.get("playStatus"),
                Boolean.parseBoolean(state.getOrDefault("isPlaying", "false")) ? 1 : 2);
        long start = parseLong(state.get("startPositionMs"),
                parseLong(state.get("position"), 0L) * 1000L);
        if (status != 1) {
            return Math.max(0L, start);
        }
        long update = parseLong(state.get("stateUpdateTimeMs"),
                parseLong(state.get("serverTime"), now));
        int rate = parseInt(state.get("playbackRate"), 100);
        long elapsed = Math.max(0L, now - update) * Math.max(rate, 1) / 100L;
        long expected = start + elapsed;
        long durationMs = parseLong(state.get("durationMs"), 0L);
        if (durationMs > 0) {
            expected = Math.min(expected, durationMs);
        }
        return Math.max(0L, expected);
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeAction(String action) {
        if (!StringUtils.hasText(action)) {
            throw new BizException(ResultCode.PARAM_INVALID, "缺少 action");
        }
        String value = action.trim();
        if (value.startsWith("room.")) {
            value = value.substring("room.".length());
        }
        return value;
    }

    private static Map<String, String> baseFields(Map<String, String> state) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("songId", state.getOrDefault("songId", ""));
        fields.put("songName", state.getOrDefault("songName", ""));
        fields.put("artistName", state.getOrDefault("artistName", ""));
        fields.put("cover", state.getOrDefault("cover", ""));
        fields.put("duration", state.getOrDefault("duration", "0"));
        fields.put("durationMs", state.getOrDefault("durationMs", "0"));
        return fields;
    }

    private static void putSong(Map<String, String> fields, Long songId, String name,
                                String artist, String cover, Integer duration) {
        // Redis Hash 不能存 null；可选字段统一落空串（见 WsPresenceService#savePlayState）
        fields.put("songId", songId == null ? "" : String.valueOf(songId));
        fields.put("songName", name == null ? "" : name);
        fields.put("artistName", artist == null ? "" : artist);
        fields.put("cover", cover == null ? "" : cover);
        fields.put("duration", duration == null ? "0" : String.valueOf(duration));
        fields.put("durationMs", duration == null ? "0" : String.valueOf(duration * 1000L));
    }

    private RoomPlayStateVO toVO(Long roomId, Map<String, Object> snapshot) {
        RoomPlayStateVO vo = new RoomPlayStateVO();
        vo.setRoomId(roomId);
        Long songId = asLong(snapshot.get("songId"));
        vo.setSongId(songId);
        // BR-22-069：点赞是「累计值」，后进房的人也必须看到，不能只靠增量广播
        vo.setLikeCount(songId == null ? 0L : stateService.likeCount(roomId, songId));
        vo.setSongName(asString(snapshot.get("songName")));
        vo.setArtistName(asString(snapshot.get("artistName")));
        vo.setCover(asString(snapshot.get("cover")));
        vo.setDurationMs(asLong(snapshot.get("durationMs")));
        vo.setPlayStatus(asInt(snapshot.get("playStatus")));
        vo.setStartPositionMs(asLong(snapshot.get("startPositionMs")));
        vo.setStateUpdateTimeMs(asLong(snapshot.get("stateUpdateTimeMs")));
        vo.setPlayStateVersion(asLong(snapshot.get("playStateVersion")));
        vo.setPlaybackRate(asInt(snapshot.get("playbackRate")));
        vo.setVolume(asInt(snapshot.get("volume")));
        vo.setUpdatedBy(asLong(snapshot.get("updatedBy")));
        vo.setServerTime(asLong(snapshot.get("serverTime")));
        vo.setPosition(asLong(snapshot.get("position")));
        vo.setIsPlaying((Boolean) snapshot.get("isPlaying"));
        return vo;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static int clampVolume(int volume) {
        return Math.max(0, Math.min(100, volume));
    }

    private static Long parseLong(String value) {
        return parseLong(value, null);
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

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(String.valueOf(value));
    }

    private static Integer asInt(Object value) {
        Long longValue = asLong(value);
        return longValue == null ? null : longValue.intValue();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
