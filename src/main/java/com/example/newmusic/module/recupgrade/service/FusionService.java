package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallItem;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多路召回融合（FR-24-002，WRF）。
 *
 * <p>{@code fused = Σ 权重 × 归一化分数 × 位置衰减}。默认 RANK 归一化：
 * 各源分数量纲完全不同（热度是次数、向量是余弦、CF 是共现），rank 是唯一天然可比的尺度。
 *
 * <p>融合阶段严格「纯内存」：这里不再查库、不再访问 Redis，候选的歌曲属性在
 * 召回结果返回时已由候选池缓存提供。
 */
@Slf4j
@Service
public class FusionService {

    private final RecConfigService configService;
    private final RecallDataService dataService;
    private final RecUpgradeProperties properties;

    public FusionService(RecConfigService configService, RecallDataService dataService,
                         RecUpgradeProperties properties) {
        this.configService = configService;
        this.dataService = dataService;
        this.properties = properties;
    }

    public List<Candidate> fuse(String scene, List<RecallResult> results) {
        RecConfigService.FusionSetting setting = configService.fusion(scene);
        if (setting == null) {
            setting = new RecConfigService.FusionSetting();
        }
        Map<String, RecConfigService.FusionSource> sourceConfig = new LinkedHashMap<>();
        if (setting.getSources() != null) {
            for (RecConfigService.FusionSource item : setting.getSources()) {
                if (item != null && item.getName() != null) {
                    sourceConfig.put(item.getName(), item);
                }
            }
        }

        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        Map<Long, Song> playable = dataService.playableMap();
        double weightSum = 0;
        List<RecallResult> usable = new ArrayList<>();
        if (results != null) {
            for (RecallResult result : results) {
                if (result != null && result.isSuccess() && !result.getItems().isEmpty()) {
                    usable.add(result);
                    RecConfigService.FusionSource config = sourceConfig.get(result.getSource());
                    weightSum += config == null ? 0.6 : Math.max(0, config.getWeight());
                }
            }
        }
        boolean equalWeight = weightSum <= 0;

        for (RecallResult result : usable) {
            RecConfigService.FusionSource config = sourceConfig.get(result.getSource());
            double weight = equalWeight ? 1.0 : (config == null ? 0.6 : Math.max(0, config.getWeight()));
            double decay = config == null ? 0.95 : clamp(config.getDecay(), 0.0, 1.0);
            double[] norms = normalize(result.getItems(), setting.getNormalize());
            for (int rank = 0; rank < result.getItems().size(); rank++) {
                RecallItem item = result.getItems().get(rank);
                if (item == null || item.getSongId() == null) {
                    continue;
                }
                Song song = playable.get(item.getSongId());
                if (song == null) {
                    continue;
                }
                double contribution = weight * norms[rank] * Math.pow(decay, rank);
                Candidate candidate = candidates.computeIfAbsent(item.getSongId(), id -> {
                    Candidate fresh = new Candidate();
                    fresh.setSongId(id);
                    fresh.setSong(song);
                    return fresh;
                });
                candidate.addSource(result.getSource(), contribution);
                candidate.setFusedScore(candidate.getFusedScore() + contribution);
                // 长尾/运营/新歌标记：来源本身是最可靠的信号
                if ("long_tail".equals(result.getSource())) {
                    candidate.setLongTail(true);
                }
                if ("editor".equals(result.getSource())) {
                    candidate.setEditor(true);
                }
                if ("new_song".equals(result.getSource())) {
                    candidate.setNewSong(true);
                }
            }
        }

        for (Candidate candidate : candidates.values()) {
            fillStaticFeatures(candidate, candidate.getSong());
        }

        List<Candidate> sorted = new ArrayList<>(candidates.values());
        // BR-24-021：确定性排序，分数相同时按 songId 升序
        sorted.sort(Comparator.comparingDouble(Candidate::getFusedScore).reversed()
                .thenComparing(Candidate::getSongId));

        int topN = setting.getTopN() > 0 ? setting.getTopN() : properties.getFusionTopN();
        List<Candidate> selected = diversify(sorted, topN, setting);
        ensureLongTail(selected, sorted, setting.getLongTailRatio(), topN);
        fillFromHotIfNeeded(scene, selected, sorted, setting.getMinCandidates(), topN);
        return selected;
    }

    // ------------------------------------------------------------------ 归一化

    private double[] normalize(List<RecallItem> items, String method) {
        int n = items.size();
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            RecallItem item = items.get(i);
            values[i] = item == null || item.getScore() == null ? 0d : item.getScore();
        }
        double[] result = new double[n];
        String mode = method == null ? "RANK" : method.toUpperCase(Locale.ROOT);
        switch (mode) {
            case "MIN_MAX": {
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                for (double v : values) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                double range = max - min;
                for (int i = 0; i < n; i++) {
                    result[i] = range <= 1e-12 ? (n == 1 ? 1.0 : 1.0 - i / (double) n)
                            : (values[i] - min) / range;
                }
                break;
            }
            case "Z_SCORE": {
                double mean = 0;
                for (double v : values) {
                    mean += v;
                }
                mean = n == 0 ? 0 : mean / n;
                double variance = 0;
                for (double v : values) {
                    variance += (v - mean) * (v - mean);
                }
                double std = n == 0 ? 0 : Math.sqrt(variance / n);
                for (int i = 0; i < n; i++) {
                    double z = std <= 1e-12 ? 0 : (values[i] - mean) / std;
                    // 把 z 压到 [0,1]：融合权重按 [0,1] 设计，保留负相关的相对次序
                    result[i] = clamp(0.5 + z / 6.0, 0.0, 1.0);
                }
                break;
            }
            case "SOFTMAX": {
                double max = -Double.MAX_VALUE;
                for (double v : values) {
                    max = Math.max(max, v);
                }
                double sum = 0;
                double[] exp = new double[n];
                for (int i = 0; i < n; i++) {
                    exp[i] = Math.exp(values[i] - max);
                    sum += exp[i];
                }
                for (int i = 0; i < n; i++) {
                    result[i] = sum <= 1e-12 ? 0 : exp[i] / sum;
                }
                break;
            }
            case "RANK":
            default: {
                // PRD: 1 - rank / N；rank 从 0 开始，最后一名仍是 1/N 而不是 0
                for (int i = 0; i < n; i++) {
                    result[i] = n <= 1 ? 1.0 : 1.0 - i / (double) n;
                }
                break;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 多样性

    /**
     * 分三轮放宽约束（EX-24-002-4）：
     * 严格（歌手 2 / 专辑 1 / 类别 40%）→ 略宽（3 / 2）→ 不限。
     * 这样既保证靠前的候选足够多样，又不会因为曲库小而凑不满。
     */
    private List<Candidate> diversify(List<Candidate> sorted, int topN,
                                      RecConfigService.FusionSetting setting) {
        int artistMax = Math.max(1, setting.getArtistMax());
        int albumMax = Math.max(1, setting.getAlbumMax());
        double categoryRatio = setting.getCategoryRatio() > 0 ? setting.getCategoryRatio() : 0.4;

        List<Candidate> selected = new ArrayList<>(Math.min(topN, sorted.size()));
        Set<Long> picked = new HashSet<>();
        select(sorted, selected, picked, topN, artistMax, albumMax, categoryRatio);
        if (selected.size() < topN) {
            select(sorted, selected, picked, topN, artistMax + 1, albumMax + 1, categoryRatio);
        }
        if (selected.size() < topN) {
            select(sorted, selected, picked, topN, Integer.MAX_VALUE, Integer.MAX_VALUE, 1.0);
        }
        return selected;
    }

    private void select(List<Candidate> sorted, List<Candidate> selected, Set<Long> picked,
                        int target, int artistMax, int albumMax, double categoryRatio) {
        Map<Long, Integer> artistCount = new HashMap<>();
        Map<Long, Integer> albumCount = new HashMap<>();
        Map<String, Integer> categoryCount = new HashMap<>();
        for (Candidate c : selected) {
            count(c, artistCount, albumCount, categoryCount);
        }
        int categoryCap = Math.max(1, (int) Math.floor(target * categoryRatio));
        for (Candidate candidate : sorted) {
            if (selected.size() >= target) {
                return;
            }
            if (picked.contains(candidate.getSongId())) {
                continue;
            }
            if (candidate.getArtistId() != null
                    && artistCount.getOrDefault(candidate.getArtistId(), 0) >= artistMax) {
                continue;
            }
            if (candidate.getAlbumId() != null
                    && albumCount.getOrDefault(candidate.getAlbumId(), 0) >= albumMax) {
                continue;
            }
            String category = category(candidate);
            if (category != null && categoryCount.getOrDefault(category, 0) >= categoryCap) {
                continue;
            }
            selected.add(candidate);
            picked.add(candidate.getSongId());
            count(candidate, artistCount, albumCount, categoryCount);
        }
    }

    private void count(Candidate c, Map<Long, Integer> artists, Map<Long, Integer> albums,
                       Map<String, Integer> categories) {
        if (c.getArtistId() != null) {
            artists.merge(c.getArtistId(), 1, Integer::sum);
        }
        if (c.getAlbumId() != null) {
            albums.merge(c.getAlbumId(), 1, Integer::sum);
        }
        String category = category(c);
        if (category != null) {
            categories.merge(category, 1, Integer::sum);
        }
    }

    /** 类别按语言优先、风格兜底：两者任一超 40% 都算「同一类」。 */
    private String category(Candidate c) {
        if (c.getLanguage() != null && !c.getLanguage().isEmpty()) {
            return "L:" + c.getLanguage();
        }
        return c.getGenre() == null || c.getGenre().isEmpty() ? null : "G:" + c.getGenre();
    }

    /** BR-24-024：长尾占比不足时，用长尾候选替换尾部。 */
    private void ensureLongTail(List<Candidate> selected, List<Candidate> sorted,
                                double minRatio, int topN) {
        if (selected.isEmpty() || minRatio <= 0) {
            return;
        }
        int target = (int) Math.ceil(selected.size() * minRatio);
        int current = 0;
        for (Candidate c : selected) {
            if (c.isLongTail()) {
                current++;
            }
        }
        if (current >= target) {
            return;
        }
        Set<Long> picked = new HashSet<>();
        for (Candidate c : selected) {
            picked.add(c.getSongId());
        }
        List<Candidate> longTailLeft = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.isLongTail() && !picked.contains(c.getSongId())) {
                longTailLeft.add(c);
            }
        }
        int need = Math.min(target - current, longTailLeft.size());
        for (int i = 0; i < need; i++) {
            // 从尾部替换，尽量不破坏头部排序
            int replaceAt = selected.size() - 1 - i;
            if (replaceAt <= 0) {
                break;
            }
            selected.set(replaceAt, longTailLeft.get(i));
        }
    }

    /** EX-24-002-5：融合后不足 20 首时用热门补齐。 */
    private void fillFromHotIfNeeded(String scene, List<Candidate> selected, List<Candidate> sorted,
                                     int minCandidates, int topN) {
        int min = Math.max(1, minCandidates);
        if (selected.size() >= min || selected.size() >= topN) {
            return;
        }
        Set<Long> picked = new LinkedHashSet<>();
        for (Candidate c : selected) {
            picked.add(c.getSongId());
        }
        for (Candidate c : sorted) {
            if (selected.size() >= min) {
                break;
            }
            if (picked.add(c.getSongId())) {
                selected.add(c);
            }
        }
        if (selected.size() >= min) {
            return;
        }
        for (Song song : dataService.playableSongs()) {
            if (selected.size() >= min) {
                break;
            }
            if (!picked.add(song.getId())) {
                continue;
            }
            Candidate candidate = new Candidate();
            candidate.setSongId(song.getId());
            candidate.setSong(song);
            candidate.setFusedScore(0d);
            candidate.setLongTail(false);
            candidate.setReason("热门补齐");
            fillStaticFeatures(candidate, song);
            candidate.addSource("hot_fill", 0d);
            selected.add(candidate);
        }
    }

    private void fillStaticFeatures(Candidate candidate, Song song) {
        if (song == null) {
            return;
        }
        candidate.setSong(song);
        candidate.setArtistId(song.getArtistId());
        candidate.setAlbumId(song.getAlbumId());
        candidate.setGenre(song.getGenre());
        candidate.setLanguage(song.getLanguage());
        candidate.setHotScore(song.getHotScore() == null ? 0 : song.getHotScore());
        candidate.setReleaseDays(dataService.releaseDays(song));
        candidate.setNewSong(candidate.isNewSong() || candidate.getReleaseDays() <= properties.getNewSongDays());
        if (song.getCompletionRate() != null) {
            candidate.setCompletionPrior(song.getCompletionRate().doubleValue());
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
