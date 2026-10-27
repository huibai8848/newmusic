package com.example.newmusic.module.recupgrade.feature;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.FeatureVector;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地特征服务（FR-24-005）。
 *
 * <p>真实系统读特征平台；本机用 Redis Hash 模拟同一套契约：
 * 用户特征 {@code ml:feat:user:{userId}}（TTL 25h）、歌曲特征 {@code ml:feat:song:{songId}}（TTL 10min）。
 * 批量读取走 pipeline，150 首候选只需要 1 次 RTT。
 *
 * <p>三条保命规则：整体 20ms 预算、缺失特征用默认值、任何异常都不让精排失败
 * （BR-24-028 / BR-24-029 / EX-24-003-3）。
 */
@Slf4j
@Service
public class LocalFeatureService {

    public static final String VERSION = "fv1";

    private final RedisOps redisOps;
    private final RecallDataService dataService;
    private final RecUpgradeProperties properties;

    public LocalFeatureService(RedisOps redisOps, RecallDataService dataService,
                               RecUpgradeProperties properties) {
        this.redisOps = redisOps;
        this.dataService = dataService;
        this.properties = properties;
    }

    public FeatureVector build(RecallContext ctx, List<Candidate> candidates) {
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(5, properties.getFeatureDeadlineMs());
        FeatureVector vector = new FeatureVector();
        vector.setFeatureVersion(VERSION);

        vector.getUserFeatures().putAll(userFeatures(ctx));
        vector.getContextFeatures().putAll(contextFeatures(ctx));

        if (candidates == null || candidates.isEmpty()) {
            vector.setCostMs(System.currentTimeMillis() - start);
            return vector;
        }

        List<String> keys = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            keys.add(RedisKeys.featureSong(candidate.getSongId()));
        }
        Map<String, Map<String, String>> cached = java.util.Collections.emptyMap();
        if (System.currentTimeMillis() < deadline) {
            try {
                cached = redisOps.hashGetAllBatch(keys);
            } catch (RuntimeException e) {
                log.warn("歌曲特征批量读取失败：{}", e.toString());
            }
        }

        for (Candidate candidate : candidates) {
            if (System.currentTimeMillis() > deadline) {
                // 超时：剩余候选全部用默认值，不阻塞精排
                vector.getSongFeatures().put(candidate.getSongId(),
                        defaults(candidate.getSongId(), candidate.getSong(), vector));
                vector.getMissingFeatures().add("song_timeout:" + candidate.getSongId());
                continue;
            }
            Map<String, String> raw = cached.get(RedisKeys.featureSong(candidate.getSongId()));
            if (raw == null || raw.isEmpty()) {
                Map<String, Double> computed = computeSongFeatures(candidate);
                vector.getSongFeatures().put(candidate.getSongId(), computed);
                // 写回缓存（每首歌一个 Hash），TTL 10 分钟
                redisOps.hashSetAll(RedisKeys.featureSong(candidate.getSongId()), toStringMap(computed));
                redisOps.expire(RedisKeys.featureSong(candidate.getSongId()),
                        Duration.ofMinutes(Math.max(1, properties.getFeatureSongTtlMinutes())));
            } else {
                Map<String, Double> parsed = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : raw.entrySet()) {
                    try {
                        parsed.put(entry.getKey(), Double.parseDouble(entry.getValue()));
                    } catch (NumberFormatException ignored) {
                        vector.getMissingFeatures().add(candidate.getSongId() + ":" + entry.getKey());
                    }
                }
                vector.getSongFeatures().put(candidate.getSongId(),
                        parsed.isEmpty() ? defaults(candidate.getSongId(), candidate.getSong(), vector) : parsed);
            }
            if (vector.getSongFeatures().get(candidate.getSongId()).isEmpty()) {
                vector.getMissingFeatures().add("song:" + candidate.getSongId());
            }
        }

        vector.setCostMs(System.currentTimeMillis() - start);
        if (vector.getCostMs() > properties.getFeatureDeadlineMs()) {
            log.debug("特征获取超过预算 {}ms（实际 {}ms）", properties.getFeatureDeadlineMs(),
                    vector.getCostMs());
        }
        return vector;
    }

    /** 用户特征：画像权重与行为强度，首次计算后缓存 25 小时。 */
    private Map<String, Double> userFeatures(RecallContext ctx) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!ctx.hasUser()) {
            result.put("is_guest", 1.0);
            result.put("behavior_count", 0.0);
            return result;
        }
        Map<String, String> cached = redisOps.hashGetAll(RedisKeys.featureUser(ctx.getUserId()));
        if (!cached.isEmpty()) {
            for (Map.Entry<String, String> entry : cached.entrySet()) {
                try {
                    result.put(entry.getKey(), Double.parseDouble(entry.getValue()));
                } catch (NumberFormatException ignored) {
                    // 跳过坏字段
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        long behaviorCount = ctx.getProfile() == null ? 0 : ctx.getProfile().getBehaviorCount();
        result.put("behavior_count", (double) behaviorCount);
        result.put("cold_start", ctx.isColdStart() ? 1.0 : 0.0);
        int stage = "S0".equals(ctx.getColdStartStage()) ? 0
                : "S1".equals(ctx.getColdStartStage()) ? 1
                : "S2".equals(ctx.getColdStartStage()) ? 2 : 3;
        result.put("cold_start_stage", (double) stage);
        result.put("is_guest", 0.0);
        if (ctx.getProfile() != null) {
            result.put("artist_pref_top", topWeight(ctx.getProfile().getArtistWeights()));
            result.put("genre_pref_top", topWeight(ctx.getProfile().getGenreWeights()));
            result.put("language_pref_top", topWeight(ctx.getProfile().getLanguageWeights()));
            result.put("tag_pref_top", topWeight(ctx.getProfile().getTagWeights()));
            result.put("liked_count", (double) ctx.getProfile().getLikedSongIds().size());
            result.put("recent_count", (double) ctx.getProfile().getRecentSongIds().size());
        }
        redisOps.hashSetAll(RedisKeys.featureUser(ctx.getUserId()), toStringMap(result));
        redisOps.expire(RedisKeys.featureUser(ctx.getUserId()),
                Duration.ofHours(Math.max(1, properties.getFeatureUserTtlHours())));
        return result;
    }

    private Map<String, Double> contextFeatures(RecallContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("hour", (double) now.getHour());
        result.put("weekday", (double) now.getDayOfWeek().getValue());
        result.put("is_weekend", now.getDayOfWeek().getValue() >= 6 ? 1.0 : 0.0);
        result.put("scene_home", "HOME_FEED".equals(ctx.getScene()) ? 1.0 : 0.0);
        result.put("has_seed", ctx.getSeedSongId() != null ? 1.0 : 0.0);
        return result;
    }

    /** 从歌曲静态属性 + 在线可得的先验计算特征（离线缓存缺失时）。 */
    private Map<String, Double> computeSongFeatures(Candidate candidate) {
        Map<String, Double> features = new LinkedHashMap<>();
        Song song = candidate.getSong();
        if (song == null) {
            return features;
        }
        long playCount = song.getPlayCount() == null ? 0 : song.getPlayCount();
        long likeCount = song.getLikeCount() == null ? 0 : song.getLikeCount();
        features.put("hot", clamp(candidate.getHotScore() / 1000.0));
        features.put("play_log", clamp(Math.log1p(playCount) / 15.0));
        features.put("like_rate", clamp(likeCount / (double) Math.max(1, playCount)));
        features.put("completion", clamp(candidate.getCompletionPrior()));
        features.put("skip", clamp(candidate.getSkipPrior()));
        features.put("tag_match", clamp(candidate.getTagMatch()));
        features.put("artist_affinity", clamp(candidate.getArtistAffinity()));
        features.put("freshness", clamp(candidate.getFreshness()));
        features.put("release_days", clamp(candidate.getReleaseDays() / 3650.0));
        features.put("duration", song.getDuration() == null ? 0.5 : clamp(song.getDuration() / 600.0));
        features.put("bpm", song.getBpm() == null ? 0.5 : clamp(song.getBpm() / 200.0));
        features.put("source_count", clamp(candidate.getSourceCount() / 5.0));
        features.put("is_editor", candidate.isEditor() ? 1.0 : 0.0);
        features.put("is_new", candidate.isNewSong() ? 1.0 : 0.0);
        features.put("is_long_tail", candidate.isLongTail() ? 1.0 : 0.0);
        return features;
    }

    private Map<String, Double> defaults(Long songId, Song song, FeatureVector vector) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("hot", 0.3);
        result.put("play_log", 0.3);
        result.put("like_rate", 0.05);
        result.put("completion", 0.5);
        result.put("skip", 0.5);
        result.put("tag_match", 0.3);
        result.put("artist_affinity", 0.3);
        result.put("freshness", 0.5);
        result.put("release_days", 0.5);
        result.put("duration", 0.5);
        result.put("bpm", 0.5);
        result.put("source_count", 0.2);
        result.put("is_editor", 0.0);
        result.put("is_new", 0.0);
        result.put("is_long_tail", 0.0);
        if (song == null) {
            vector.getMissingFeatures().add("song:" + songId);
        }
        return result;
    }

    private Map<String, String> toStringMap(Map<String, Double> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value == null ? "0" : String.valueOf(value)));
        return result;
    }

    private double topWeight(Map<?, Double> weights) {
        double max = 0;
        for (Double value : weights.values()) {
            max = Math.max(max, value == null ? 0 : value);
        }
        return clamp(max);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 离线/在线特征一致性抽检（FR-24-005，任务每日跑）。 */
    public Map<String, Object> consistencySample(int sampleSize) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Song> songs = dataService.playableSongs();
        int size = Math.min(sampleSize, songs.size());
        double onlineSum = 0;
        double offlineSum = 0;
        int compared = 0;
        for (int i = 0; i < size; i++) {
            Song song = songs.get(i);
            long playCount = song.getPlayCount() == null ? 0 : song.getPlayCount();
            double offline = clamp(Math.log1p(playCount) / 15.0);
            Map<String, String> cached = redisOps.hashGetAll(RedisKeys.featureSong(song.getId()));
            if (cached.isEmpty()) {
                continue;
            }
            try {
                onlineSum += Double.parseDouble(cached.getOrDefault("play_log", "0"));
                offlineSum += offline;
                compared++;
            } catch (NumberFormatException ignored) {
                // 坏样本不计入
            }
        }
        double onlineMean = compared == 0 ? 0 : onlineSum / compared;
        double offlineMean = compared == 0 ? 0 : offlineSum / compared;
        double deviation = offlineMean == 0 ? 0 : Math.abs(onlineMean - offlineMean) / offlineMean;
        report.put("feature", "play_log");
        report.put("sampleSize", compared);
        report.put("onlineMean", onlineMean);
        report.put("offlineMean", offlineMean);
        report.put("deviation", deviation);
        report.put("passed", deviation <= 0.05);
        return report;
    }
}
