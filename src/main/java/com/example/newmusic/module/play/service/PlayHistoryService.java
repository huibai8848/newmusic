package com.example.newmusic.module.play.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.play.dto.EndPlayReq;
import com.example.newmusic.module.play.dto.PlayProgressVO;
import com.example.newmusic.module.play.dto.PlayRecordVO;
import com.example.newmusic.module.play.dto.ProgressReportReq;
import com.example.newmusic.module.play.dto.RecordPlayReq;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端播放历史（模块 07 FR-16-004 的最小落地，模块 08 的行为数据前置）。
 *
 * <p>模块 07 的系分把历史/进度同步明确推迟到了模块 12，但模块 08 的个性化推荐
 * 没有服务端行为数据就无从谈起，因此在这里实现「开始 / 进度 / 结束 / 列表」四个动作。
 * 与模块 12 将来的差异只会在数据规模与聚合方式，接口语义保持一致。
 */
@Slf4j
@Service
public class PlayHistoryService {

    /** BR-16-012：5 秒内重复开始上报视为同一次播放 */
    private static final Duration DEDUP_TTL = Duration.ofSeconds(5);
    /** 最近播放 ZSet 最大长度（BR-16-026） */
    private static final int RECENT_LIMIT = 500;
    private static final Duration RECENT_TTL = Duration.ofDays(30);
    private static final Duration PROGRESS_TTL = Duration.ofDays(30);

    /** BR-17 的有效播放口径：≥30 秒或 ≥60% */
    private static final int VALID_SECONDS = 30;
    private static final double VALID_RATIO = 0.6;

    private final PlayHistoryMapper historyMapper;
    private final SongService songService;
    private final RedisOps redisOps;
    private final PlayDeviceService playDeviceService;

    public PlayHistoryService(PlayHistoryMapper historyMapper, SongService songService, RedisOps redisOps,
                              PlayDeviceService playDeviceService) {
        this.historyMapper = historyMapper;
        this.songService = songService;
        this.redisOps = redisOps;
        this.playDeviceService = playDeviceService;
    }

    /**
     * 播放开始上报。
     *
     * <p>校验走 {@link SongService#requirePlayable(Long)}，与播放地址签发同一口径，
     * 避免「能上报不能播放」或「下架歌进入推荐画像」。
     */
    @Transactional
    public PlayRecordVO record(RecordPlayReq req, Long userId) {
        return record(req, userId, null);
    }

    /**
     * 播放开始上报（带客户端 IP）。
     *
     * <p>IP 只用于设备列表展示，取不到不影响任何逻辑。
     */
    @Transactional
    public PlayRecordVO record(RecordPlayReq req, Long userId, String ip) {
        Song song = songService.requirePlayable(req.getSongId());
        String subject = subject(userId, req.getDeviceId());
        // 模块 12：登录用户的播放设备登记（游客不登记，避免把匿名设备塞进账号）
        playDeviceService.touch(userId, req.getDeviceId(), req.getDeviceName(), req.getDeviceType(), ip);

        // SETNX 抢占窗口：只有第一个请求写历史，其余返回同一条记录，避免 StrictMode
        // 或用户连点造成的行为膨胀（BR-16-012）。
        // Redis 不可用时 setIfAbsent 固定返回 false；这里必须放行，
        // 否则一次缓存故障会让所有播放行为都写不进数据库。
        boolean first = !redisOps.isAvailable()
                || redisOps.setIfAbsent(RedisKeys.playDedup(subject, req.getSongId()), "1", DEDUP_TTL);
        if (!first) {
            PlayHistory latest = latestForSubject(userId, req.getDeviceId(), req.getSongId());
            PlayRecordVO vo = new PlayRecordVO();
            vo.setDuplicated(true);
            vo.setSongId(req.getSongId());
            vo.setSongName(song.getName());
            vo.setArtistName(song.getArtistName());
            vo.setPlayId(latest == null ? null : latest.getId());
            return vo;
        }

        PlayHistory history = new PlayHistory();
        history.setUserId(userId);
        history.setDeviceId(userId == null ? req.getDeviceId() : null);
        history.setSongId(song.getId());
        history.setSongName(song.getName());
        history.setArtistId(song.getArtistId());
        history.setArtistName(song.getArtistName());
        history.setGenre(song.getGenre());
        history.setLanguage(song.getLanguage());
        history.setQuality(qualityCode(req.getQuality()));
        history.setPlayDuration(0);
        history.setSongDuration(song.getDuration() == null ? 0 : song.getDuration());
        history.setLastPosition(BigDecimal.ZERO);
        history.setIsValid(0);
        history.setSource(StringUtils.hasText(req.getSource()) ? req.getSource() : "unknown");
        history.setRoomId(req.getRoomId());
        history.setPlayTime(LocalDateTime.now());
        historyMapper.insert(history);

        if (userId != null) {
            String recentKey = RedisKeys.playRecent(userId);
            redisOps.zAdd(recentKey, String.valueOf(song.getId()), System.currentTimeMillis(), RECENT_TTL);
            // 只保留最近 500 首：分数是时间戳，新的在高位
            redisOps.zRemRangeByRank(recentKey, 0, -(RECENT_LIMIT + 1));
            // BR-17-029：新行为让猜你喜欢缓存立即失效
            redisOps.delete(RedisKeys.recGuess(userId));
        }

        PlayRecordVO vo = new PlayRecordVO();
        vo.setDuplicated(false);
        vo.setSongId(song.getId());
        vo.setSongName(song.getName());
        vo.setArtistName(song.getArtistName());
        vo.setPlayId(history.getId());
        return vo;
    }

    /**
     * 进度上报。前端约每 30 秒一次 + 切歌/离开页面时各一次。
     *
     * <p>Redis Hash 是「继续播放」的快速读源，DB 的 last_position 是持久化落点；
     * 两者都写，Redis 故障时 DB 仍然可用。
     */
    public void progress(ProgressReportReq req, Long userId) {
        progress(req, userId, null);
    }

    /** 进度上报（带客户端 IP）；deviceId 同时写进 Redis 进度 Hash，供跨设备续播读取。 */
    public void progress(ProgressReportReq req, Long userId, String ip) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        double position = req.getPosition() == null ? 0 : req.getPosition();
        double duration = req.getDuration() == null ? 0 : req.getDuration();
        playDeviceService.touch(userId, req.getDeviceId(), req.getDeviceName(), req.getDeviceType(), ip);

        PlayHistory row = req.getPlayId() == null
                ? historyMapper.selectLatestBySong(userId, req.getSongId())
                : historyMapper.selectById(req.getPlayId());
        if (row != null && !userId.equals(row.getUserId())) {
            row = null;
        }
        if (row != null) {
            if (duration > 0) {
                row.setSongDuration((int) Math.round(duration));
            }
            row.setLastPosition(BigDecimal.valueOf(Math.max(0, position)));
            // 进度本身不改变有效播放，但续播时也要记录实际播放秒数（取进度与旧值的较大者）
            int played = (int) Math.round(position);
            if (played > (row.getPlayDuration() == null ? 0 : row.getPlayDuration())) {
                row.setPlayDuration(played);
            }
            historyMapper.updateById(row);
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("position", String.valueOf(position));
        values.put("duration", String.valueOf(duration));
        values.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        if (req.getPlayId() != null) {
            values.put("playId", String.valueOf(req.getPlayId()));
        }
        if (StringUtils.hasText(req.getDeviceId())) {
            values.put("deviceId", req.getDeviceId().trim());
        }
        String key = RedisKeys.playProgress(userId, req.getSongId());
        redisOps.hashSetAll(key, values);
        redisOps.expire(key, PROGRESS_TTL);
    }

    /**
     * 播放结束上报。播放时长达到「≥30 秒或 ≥60%」才计为有效播放，
     * 并只在首次从无效变有效时累加歌曲播放量，避免重复上报刷量。
     */
    @Transactional
    public void end(EndPlayReq req, Long userId) {
        PlayHistory row = historyMapper.selectById(req.getPlayId());
        if (row == null) {
            return;
        }
        if (userId != null) {
            if (!userId.equals(row.getUserId())) {
                return;
            }
        } else if (!StringUtils.hasText(req.getDeviceId()) || !req.getDeviceId().equals(row.getDeviceId())) {
            // 游客的结束上报必须带创建时的 deviceId，否则无法证明归属
            return;
        }

        int played = req.getPlayedDuration() == null ? 0 : req.getPlayedDuration();
        int songDuration = req.getSongDuration() != null && req.getSongDuration() > 0
                ? req.getSongDuration()
                : (row.getSongDuration() == null ? 0 : row.getSongDuration());

        boolean valid = played >= VALID_SECONDS
                || (songDuration > 0 && played >= songDuration * VALID_RATIO)
                || Boolean.TRUE.equals(req.getCompleted());
        boolean wasValid = row.getIsValid() != null && row.getIsValid() == 1;

        row.setPlayDuration(played);
        row.setSongDuration(songDuration);
        // 优先使用前端报的「结束位置」：playedDuration 是实际收听秒数，
        // 用户拖过进度条时两者并不相等，拿 played 当续播点会把进度往回拉。
        if (req.getPosition() != null && req.getPosition() >= 0) {
            row.setLastPosition(BigDecimal.valueOf(req.getPosition()));
        } else if (songDuration > 0 && played > 0) {
            row.setLastPosition(BigDecimal.valueOf(Math.min(played, songDuration)));
        }
        row.setIsValid(valid ? 1 : 0);
        historyMapper.updateById(row);

        if (valid && !wasValid) {
            songService.incrementCounter(row.getSongId(), "play_count", 1);
        }
    }

    /** 播放历史：每首歌只保留最近一次（BR-16-027）。 */
    public PageResult<PlayHistory> history(Long userId, int pageNum, int pageSize) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        long safePage = Math.max(1, pageNum);
        long safeSize = Math.min(100, Math.max(1, pageSize));
        long total = historyMapper.countDistinctSongs(userId);
        List<PlayHistory> list = total == 0
                ? Collections.<PlayHistory>emptyList()
                : historyMapper.selectLatestPage(userId, (safePage - 1) * safeSize, safeSize);
        PageResult<PlayHistory> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPageNum(safePage);
        result.setPageSize(safeSize);
        long pages = (total + safeSize - 1) / safeSize;
        result.setPages(pages);
        result.setHasNext(safePage < pages);
        result.setTruncated(false);
        return result;
    }

    /** 删除一条历史（按历史行 ID，而不是 songId）。 */
    public void delete(Long userId, Long historyId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        PlayHistory row = historyMapper.selectById(historyId);
        if (row == null || !userId.equals(row.getUserId())) {
            return;
        }
        historyMapper.deleteById(historyId);
    }

    /** 清空历史。逻辑删除由 @TableLogic 自动追加 deleted=0 条件。 */
    public void clear(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        historyMapper.delete(new LambdaQueryWrapper<PlayHistory>().eq(PlayHistory::getUserId, userId));
        redisOps.delete(RedisKeys.playRecent(userId));
        redisOps.delete(RedisKeys.recGuess(userId));
    }

    /** 最近播放的歌曲 ID（Redis 优先，Redis 不可用时回源 DB）。 */
    public List<Long> recentSongIds(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<String> raw = redisOps.zRevRange(RedisKeys.playRecent(userId), 0, limit - 1);
        if (!raw.isEmpty()) {
            return toLongs(raw);
        }
        if (!redisOps.isAvailable()) {
            return historyMapper.selectRecentSongIds(userId, limit);
        }
        // Redis 可用但键不存在（可能是重启或被清理），同样回源 DB，
        // 这样推荐不会因为缓存丢失而突然「失忆」
        return historyMapper.selectRecentSongIds(userId, limit);
    }

    /** 最近 90 天行为明细，供推荐画像使用。 */
    public List<PlayHistory> recentHistory(Long userId, int days, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return historyMapper.selectRecentHistory(userId, LocalDateTime.now().minusDays(days), limit);
    }

    public long behaviorCount(Long userId) {
        return userId == null ? 0 : historyMapper.countByUser(userId);
    }

    public Optional<PlayHistory> latestProgress(Long userId, Long songId) {
        if (userId == null || songId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(historyMapper.selectLatestBySong(userId, songId));
    }

    /**
     * 跨设备续播：读取最近一次进度（模块 12 FR-21-007）。
     *
     * <p>Redis 是快速读源，DB 的 {@code last_position} 是持久化兜底。返回值刻意不做
     * 「自动跳到该位置」的决定——那由前端在用户点击后执行（BR-21-066）。
     */
    public PlayProgressVO playProgress(Long userId, Long songId, String deviceId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (songId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "songId 不能为空");
        }
        String current = StringUtils.hasText(deviceId) ? deviceId.trim() : null;
        PlayProgressVO vo = new PlayProgressVO();
        vo.setSongId(songId);

        Map<String, String> hash = redisOps.hashGetAll(RedisKeys.playProgress(userId, songId));
        Double position = parseDouble(hash.get("position"));
        if (position != null) {
            vo.setFound(true);
            vo.setSource("REDIS");
            vo.setPosition(Math.max(0, position));
            Double duration = parseDouble(hash.get("duration"));
            vo.setDuration(duration == null ? 0 : Math.max(0, duration));
            Long playId = parseLong(hash.get("playId"));
            vo.setPlayId(playId);
            String reportedBy = hash.get("deviceId");
            vo.setDeviceId(reportedBy);
            Long updatedAt = parseLong(hash.get("updatedAt"));
            vo.setUpdateTime(updatedAt == null ? 0 : updatedAt);
            vo.setFromCurrentDevice(current != null && current.equals(reportedBy));
            return vo;
        }

        PlayHistory row = historyMapper.selectLatestBySong(userId, songId);
        if (row != null && row.getLastPosition() != null) {
            vo.setFound(true);
            vo.setSource("DB");
            vo.setPosition(Math.max(0, row.getLastPosition().doubleValue()));
            vo.setDuration(row.getSongDuration() == null ? 0 : row.getSongDuration());
            vo.setPlayId(row.getId());
            vo.setDeviceId(null);
            LocalDateTime updated = row.getUpdateTime() != null ? row.getUpdateTime() : row.getPlayTime();
            if (updated != null) {
                vo.setUpdateTime(updated.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            vo.setFromCurrentDevice(false);
            return vo;
        }

        vo.setSource("NONE");
        return vo;
    }

    public List<PlayHistory> continueRows(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return historyMapper.selectContinueRows(userId, limit);
    }

    public List<PlayHistoryMapper.FrequentArtistRow> frequentArtists(Long userId, int days, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return historyMapper.selectFrequentArtists(userId, LocalDateTime.now().minusDays(days), limit);
    }

    private PlayHistory latestForSubject(Long userId, String deviceId, Long songId) {
        LambdaQueryWrapper<PlayHistory> wrapper = new LambdaQueryWrapper<PlayHistory>()
                .eq(PlayHistory::getSongId, songId)
                .eq(userId != null, PlayHistory::getUserId, userId)
                .eq(userId == null, PlayHistory::getDeviceId, deviceId)
                .orderByDesc(PlayHistory::getPlayTime)
                .last("LIMIT 1");
        return historyMapper.selectOne(wrapper);
    }

    private String subject(Long userId, String deviceId) {
        if (userId != null) {
            return "u:" + userId;
        }
        if (!StringUtils.hasText(deviceId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "游客播放必须携带 deviceId");
        }
        return "d:" + deviceId;
    }

    private int qualityCode(String quality) {
        if ("lossless".equalsIgnoreCase(quality)) {
            return 3;
        }
        if ("high".equalsIgnoreCase(quality)) {
            return 2;
        }
        return 1;
    }

    private static Double parseDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Long> toLongs(List<String> raw) {
        List<Long> ids = new ArrayList<>(raw.size());
        for (String value : raw) {
            try {
                ids.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过即可，不影响其它推荐
            }
        }
        return ids;
    }
}
