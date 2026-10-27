package com.example.newmusic.module.room.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.vo.RoomRankEntryVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.service.WsPresenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间榜单 / 点赞 / 收听时长（FR-22-005 ~ FR-22-007）。
 *
 * <p>四个榜单都是 Redis ZSet：{@code gift / active / request / listen}。
 * 分数只增不减，24 小时后随房间域一起过期；「选择不上榜」是读侧过滤，
 * 不删除成员，用户取消隐身即可恢复。
 *
 * <p><b>活跃分权重</b>（BR-22-047）：消息 ×1、点歌 ×5、投票 ×2、礼物 ×10。
 * 权重集中在本类，避免散落到聊天/队列/礼物各处后对不上账。
 */
@Slf4j
@Service
public class RoomRankService {

    public static final String TYPE_GIFT = "gift";
    public static final String TYPE_ACTIVE = "active";
    public static final String TYPE_REQUEST = "request";
    public static final String TYPE_LISTEN = "listen";

    private static final List<String> TYPES = Collections.unmodifiableList(
            Arrays.asList(TYPE_GIFT, TYPE_ACTIVE, TYPE_REQUEST, TYPE_LISTEN));

    /** 活跃分权重（BR-22-047）。 */
    private static final double WEIGHT_MESSAGE = 1d;
    private static final double WEIGHT_REQUEST = 5d;
    private static final double WEIGHT_VOTE = 2d;
    private static final double WEIGHT_GIFT = 10d;

    private static final int VIEW_LIMIT = 100;
    /** 单次收听上报最多计 60 秒，防止客户端伪造超长时长。 */
    private static final int MAX_LISTEN_SECONDS = 60;

    private final RoomStateService stateService;
    private final RoomPermissionService permissionService;
    private final WsPresenceService presence;
    private final RoomNotifier notifier;
    private final UserService userService;

    public RoomRankService(RoomStateService stateService,
                           RoomPermissionService permissionService,
                           WsPresenceService presence, RoomNotifier notifier,
                           UserService userService) {
        this.stateService = stateService;
        this.permissionService = permissionService;
        this.presence = presence;
        this.notifier = notifier;
        this.userService = userService;
    }

    // ------------------------------------------------------------------ 计分

    /** 聊天/弹幕：活跃 +1。 */
    public void recordMessage(Long roomId, Long userId) {
        recordActive(roomId, userId, WEIGHT_MESSAGE);
    }

    /** 点歌/合并点歌：活跃 +5、点歌榜 +1。 */
    public void recordRequest(Long roomId, Long userId) {
        if (roomId == null || userId == null) {
            return;
        }
        recordActive(roomId, userId, WEIGHT_REQUEST);
        stateService.incrRank(roomId, TYPE_REQUEST, userId, 1d);
        notifyTopIfNeeded(roomId, TYPE_REQUEST);
    }

    /** 投票：活跃 +2。 */
    public void recordVote(Long roomId, Long userId) {
        recordActive(roomId, userId, WEIGHT_VOTE);
    }

    /** 送礼：活跃 +10、礼物榜按礼物价值累计（由礼物模块传入）。 */
    public void recordGift(Long roomId, Long userId, double giftValue) {
        if (roomId == null || userId == null) {
            return;
        }
        recordActive(roomId, userId, WEIGHT_GIFT);
        stateService.incrRank(roomId, TYPE_GIFT, userId, Math.max(giftValue, 1d));
        notifyTopIfNeeded(roomId, TYPE_GIFT);
    }

    public void recordActive(Long roomId, Long userId, double delta) {
        if (roomId == null || userId == null || delta <= 0) {
            return;
        }
        stateService.incrRank(roomId, TYPE_ACTIVE, userId, delta);
        notifyTopIfNeeded(roomId, TYPE_ACTIVE);
    }

    // ------------------------------------------------------------------ 查询

    public List<RoomRankEntryVO> top(Long roomId, String type, int limit) {
        permissionService.requireRoom(roomId);
        String normalized = normalizeType(type);
        int size = limit <= 0 ? 10 : Math.min(limit, 50);
        Map<String, Double> scores = stateService.topRank(roomId, normalized, VIEW_LIMIT);
        List<RoomRankEntryVO> result = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            Long userId = parseLong(entry.getKey());
            if (userId == null || result.size() >= size) {
                continue;
            }
            if (stateService.isRankHidden(roomId, userId)) {
                continue;
            }
            RoomRankEntryVO vo = new RoomRankEntryVO();
            vo.setRank(rank++);
            vo.setUserId(userId);
            vo.setScore(Math.round(entry.getValue() == null ? 0d : entry.getValue()));
            fillUser(vo, userId);
            result.add(vo);
            if (result.size() >= size) {
                break;
            }
        }
        return result;
    }

    public void setHidden(Long roomId, Long userId, boolean hidden) {
        permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        stateService.setRankHidden(roomId, userId, hidden);
    }

    public boolean isHidden(Long roomId, Long userId) {
        return stateService.isRankHidden(roomId, userId);
    }

    // ------------------------------------------------------------------ 点赞

    /**
     * 对当前歌曲点赞（FR-22-006）。每人每歌只计一次，重复点击幂等返回当前总数。
     *
     * @return {@code likeCount / liked / songId}
     */
    public Map<String, Object> like(Long roomId, Long userId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        Long songId = parseLong(presence.playState(roomId).get("songId"));
        if (songId == null) {
            throw new BizException(ResultCode.ROOM_VOTE_NOT_ALLOWED, "当前没有正在播放的歌曲");
        }
        boolean liked = stateService.likeOnce(roomId, songId, userId);
        long count = liked ? stateService.incrLike(roomId, songId) : stateService.likeCount(roomId, songId);
        if (liked && stateService.tryLikeNotify(roomId)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roomId", roomId);
            data.put("songId", songId);
            data.put("likeCount", count);
            data.put("userId", userId);
            notifier.roomEvent(roomId, "ROOM_LIKE_UPDATE", data);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", roomId);
        result.put("songId", songId);
        result.put("liked", liked);
        result.put("likeCount", count);
        return result;
    }

    public long likeCount(Long roomId, Long songId) {
        return songId == null ? 0L : stateService.likeCount(roomId, songId);
    }

    // ------------------------------------------------------------------ 收听时长

    /**
     * 收听时长上报（FR-22-007）。
     *
     * <p>反作弊只信「两次上报之间的真实间隔」：同一用户第二次上报能计入的秒数不超过
     * 实际间隔 + 2 秒容差；首次上报最多计 30 秒。暂停/结束状态下不计分。
     *
     * @return {@code acceptedSeconds / listenScore}
     */
    public Map<String, Object> reportListen(Long roomId, Long userId, int seconds) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        Map<String, String> state = presence.playState(roomId);
        int playStatus = parseInt(state.get("playStatus"),
                Boolean.parseBoolean(state.getOrDefault("isPlaying", "false")) ? 1 : 2);
        if (playStatus != 1) {
            return listenResult(0, stateService.topRank(roomId, TYPE_LISTEN, 1)
                    .getOrDefault(String.valueOf(userId), 0d));
        }

        long now = System.currentTimeMillis();
        long last = stateService.lastListenReport(roomId, userId);
        int accepted;
        if (last <= 0) {
            accepted = Math.min(seconds, 30);
        } else {
            long elapsedSeconds = Math.max(0L, (now - last) / 1000L);
            accepted = (int) Math.min(seconds, Math.min(MAX_LISTEN_SECONDS, elapsedSeconds + 2));
        }
        if (accepted <= 0) {
            return listenResult(0, stateService.topRank(roomId, TYPE_LISTEN, 1)
                    .getOrDefault(String.valueOf(userId), 0d));
        }
        stateService.markListenReport(roomId, userId, now);
        double score = stateService.addListenSeconds(roomId, userId, accepted);
        notifyTopIfNeeded(roomId, TYPE_LISTEN);
        return listenResult(accepted, score);
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> listenResult(int accepted, double score) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acceptedSeconds", accepted);
        result.put("listenScore", Math.round(score));
        result.put("serverTime", System.currentTimeMillis());
        return result;
    }

    /** 榜单变化播报，同一类型 1 分钟最多 1 次（BR-22-051）。 */
    private void notifyTopIfNeeded(Long roomId, String type) {
        if (!stateService.tryRankNotify(roomId, type)) {
            return;
        }
        try {
            List<RoomRankEntryVO> top3 = top(roomId, type, 3);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roomId", roomId);
            data.put("type", type);
            data.put("top", top3);
            data.put("serverTime", System.currentTimeMillis());
            notifier.roomEvent(roomId, "ROOM_RANK_UPDATE", data);
        } catch (Exception e) {
            log.debug("榜单播报失败 roomId={} type={} cause={}", roomId, type, e.toString());
        }
    }

    private void fillUser(RoomRankEntryVO vo, Long userId) {
        try {
            User user = userService.getById(userId);
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
        } catch (Exception e) {
            log.debug("榜单用户信息读取失败 userId={}", userId);
        }
    }

    private static String normalizeType(String type) {
        String value = type == null ? TYPE_ACTIVE : type.trim().toLowerCase();
        if (!TYPES.contains(value)) {
            throw new BizException(ResultCode.ROOM_RANK_TYPE_INVALID);
        }
        return value;
    }

    private static Long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static List<String> types() {
        return TYPES;
    }
}
