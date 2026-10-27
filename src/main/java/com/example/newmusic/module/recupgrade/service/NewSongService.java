package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.mapper.RecEventMapper;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新歌扶持（FR-24-006 的歌曲侧，P1）。
 *
 * <p>曝光控制是「三层闸门」：
 * <ol>
 *   <li>单曲 24h 曝光达到 1000 → 停止推荐该曲；</li>
 *   <li>单用户每天最多看到 2 首新歌（避免整屏新歌）；</li>
 *   <li>每日扶持新歌不超过 50 首；</li>
 *   <li>完成率低于 30%（样本 ≥100 曝光）→ 当日停止扶持。</li>
 * </ol>
 *
 * <p>所有键都在 {@code ml:rec:newsong:*} 下，按天分键，TTL 24 小时。
 */
@Slf4j
@Service
public class NewSongService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisOps redisOps;
    private final RecUpgradeProperties properties;
    private final RecallDataService dataService;
    private final RecEventMapper eventMapper;

    public NewSongService(RedisOps redisOps, RecUpgradeProperties properties,
                          RecallDataService dataService, RecEventMapper eventMapper) {
        this.redisOps = redisOps;
        this.properties = properties;
        this.dataService = dataService;
        this.eventMapper = eventMapper;
    }

    public boolean canExpose(RecallContext ctx, Candidate candidate) {
        if (candidate == null || !candidate.isNewSong() || candidate.getSongId() == null) {
            return true;
        }
        String day = today();
        if (redisOps.isMember(RedisKeys.recNewSongStopped(day), String.valueOf(candidate.getSongId()))) {
            return false;
        }
        long exposure = parse(redisOps.get(RedisKeys.recNewSongExposure(candidate.getSongId())).orElse("0"));
        if (exposure >= properties.getNewSongExposureTarget()) {
            return false;
        }
        if (ctx.hasUser()) {
            long userExposure = parse(redisOps.get(
                    RedisKeys.recNewSongUser(ctx.getUserId(), day)).orElse("0"));
            if (userExposure >= properties.getNewSongUserExposureLimit()) {
                return false;
            }
        }
        if (redisOps.setSize(RedisKeys.recNewSongPromoted(day)) >= properties.getNewSongDailyCap()
                && !redisOps.isMember(RedisKeys.recNewSongPromoted(day),
                String.valueOf(candidate.getSongId()))) {
            return false;
        }
        return true;
    }

    /** 最终列表确定后调用：只给真正返回的新歌记曝光。 */
    public void recordExposure(RecallContext ctx, List<Candidate> finalList) {
        if (finalList == null || finalList.isEmpty()) {
            return;
        }
        String day = today();
        Set<Long> counted = new HashSet<>();
        for (Candidate candidate : finalList) {
            if (candidate == null || !candidate.isNewSong() || candidate.getSongId() == null) {
                continue;
            }
            if (!counted.add(candidate.getSongId())) {
                continue;
            }
            redisOps.increment(RedisKeys.recNewSongExposure(candidate.getSongId()), Duration.ofHours(24));
            redisOps.addToSet(RedisKeys.recNewSongPromoted(day), String.valueOf(candidate.getSongId()),
                    Duration.ofHours(24));
            if (ctx.hasUser()) {
                redisOps.increment(RedisKeys.recNewSongUser(ctx.getUserId(), day), Duration.ofHours(24));
            }
        }
    }

    /**
     * 每日评估：曝光 ≥100 且完成率 <30% 的新歌当日停止扶持。
     *
     * @return 被停止的歌曲数
     */
    public int evaluateAndStop() {
        List<Song> newSongs = dataService.newSongs(500);
        if (newSongs.isEmpty()) {
            return 0;
        }
        Set<Long> newIds = new HashSet<>();
        for (Song song : newSongs) {
            newIds.add(song.getId());
        }
        String day = today();
        int stopped = 0;
        try {
            for (RecEventMapper.NewSongStatRow row : eventMapper.selectNewSongStats(
                    LocalDateTime.now().minusDays(1))) {
                if (row.getSongId() == null || !newIds.contains(row.getSongId())) {
                    continue;
                }
                long exposure = row.getExposure() == null ? 0 : row.getExposure();
                long complete = row.getCompleteCount() == null ? 0 : row.getCompleteCount();
                if (exposure >= 100 && complete / (double) exposure < properties.getNewSongCompletionThreshold()) {
                    redisOps.addToSet(RedisKeys.recNewSongStopped(day), String.valueOf(row.getSongId()),
                            Duration.ofHours(24));
                    stopped++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("新歌效果评估失败：{}", e.toString());
        }
        return stopped;
    }

    /** 后台展示：今日新歌扶持状态。 */
    public Map<String, Object> status() {
        String day = today();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("date", day);
        result.put("promoted", redisOps.members(RedisKeys.recNewSongPromoted(day)));
        result.put("stopped", redisOps.members(RedisKeys.recNewSongStopped(day)));
        result.put("dailyCap", properties.getNewSongDailyCap());
        result.put("exposureTarget", properties.getNewSongExposureTarget());
        result.put("completionThreshold", properties.getNewSongCompletionThreshold());
        return result;
    }

    /** 后台手动停止/恢复某首新歌的扶持。 */
    public void stop(Long songId, boolean stop) {
        String day = today();
        if (stop) {
            redisOps.addToSet(RedisKeys.recNewSongStopped(day), String.valueOf(songId), Duration.ofHours(24));
        } else {
            redisOps.removeFromSet(RedisKeys.recNewSongStopped(day), String.valueOf(songId));
        }
    }

    private String today() {
        return LocalDate.now().format(DATE);
    }

    private long parse(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
