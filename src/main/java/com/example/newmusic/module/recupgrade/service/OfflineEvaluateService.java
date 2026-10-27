package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.Murmur3;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线评估（FR-24-010 / P1）。
 *
 * <p><b>严格时间切分</b>：训练集是测试日之前的 N 天，测试集是最后 1 天，绝不随机切分——
 * 随机切分会让「未来信息」泄漏进训练集，指标好看但没有意义（BR-24-095）。
 *
 * <p>本机没有 Spark，评估直接跑在 MySQL 播放历史上；用户数默认上限 500，
 * 足够验证一次排序改动是正向还是负向。结果写入 {@code ml:rec:evaluate:{taskId}}，
 * taskId 由参数哈希决定，重复跑同一天同一配置会覆盖同一份档案（可复现）。
 */
@Slf4j
@Service
public class OfflineEvaluateService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration RESULT_TTL = Duration.ofDays(7);
    private static final int TOP_K = 50;

    private final PlayHistoryMapper historyMapper;
    private final RecallDataService dataService;
    private final RecUpgradeProperties properties;
    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;

    public OfflineEvaluateService(PlayHistoryMapper historyMapper, RecallDataService dataService,
                                  RecUpgradeProperties properties, RedisOps redisOps,
                                  ObjectMapper objectMapper) {
        this.historyMapper = historyMapper;
        this.dataService = dataService;
        this.properties = properties;
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> evaluate(Integer maxUsers, Integer trainDays) {
        int limit = clamp(maxUsers == null ? properties.getEvaluateMaxUsers() : maxUsers, 1, 2000);
        int windowDays = clamp(trainDays == null ? properties.getEvaluateTrainDays() : trainDays, 1, 30);
        LocalDateTime testEnd = LocalDate.now().atStartOfDay();
        LocalDateTime testStart = testEnd.minusDays(1);
        LocalDateTime trainStart = testStart.minusDays(windowDays);

        List<Song> catalog = dataService.playableSongs();
        Map<Long, Song> byId = new HashMap<>();
        Map<Long, Integer> popularityRank = new HashMap<>();
        for (int i = 0; i < catalog.size(); i++) {
            Song song = catalog.get(i);
            byId.put(song.getId(), song);
            // playableSongs() 已按热度倒序，下标就是流行度名次
            popularityRank.put(song.getId(), i);
        }
        List<Long> users = historyMapper.selectActiveUserIds(trainStart, limit);

        int evaluated = 0;
        int skipped = 0;
        double hit10 = 0;
        double hit30 = 0;
        double ndcg10 = 0;
        double ndcg30 = 0;
        double map10 = 0;
        double auc = 0;
        double logLoss = 0;
        double novelty = 0;
        Set<Long> recommended = new HashSet<>();

        for (Long userId : users) {
            List<Long> trainIds = historyMapper.selectSongIdsBetween(userId, trainStart, testStart);
            List<Long> testIds = historyMapper.selectSongIdsBetween(userId, testStart, testEnd);
            if (trainIds.isEmpty() || testIds.isEmpty()) {
                skipped++;
                continue;
            }
            Set<Long> test = new HashSet<>(testIds);
            Map<Long, Double> ranked = scoreForUser(trainIds, catalog, byId);
            List<Long> top = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : ranked.entrySet()) {
                if (top.size() >= TOP_K) {
                    break;
                }
                top.add(entry.getKey());
            }
            // 训练期听过的歌不再推荐，测试集命中只看「新歌」
            Set<Long> recommendedForUser = new HashSet<>(top);
            recommended.addAll(recommendedForUser);

            hit10 += hits(top, test, 10) > 0 ? 1 : 0;
            hit30 += hits(top, test, 30) > 0 ? 1 : 0;
            ndcg10 += ndcg(top, test, 10);
            ndcg30 += ndcg(top, test, 30);
            map10 += averagePrecision(top, test, 10);

            double[] aucLoss = aucAndLogLoss(ranked, test);
            auc += aucLoss[0];
            logLoss += aucLoss[1];
            novelty += novelty(top, popularityRank, catalog.size());
            evaluated++;
        }

        if (evaluated == 0) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("taskId", taskId(windowDays, limit));
            empty.put("status", "NO_DATA");
            empty.put("note", "训练期或测试期没有足够的播放行为；先在测试环境产生播放历史");
            empty.put("evaluatedUsers", 0);
            empty.put("skippedUsers", skipped);
            empty.put("trainDays", windowDays);
            empty.put("testDays", 1);
            archive(taskId(windowDays, limit), empty);
            return empty;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId(windowDays, limit));
        result.put("status", "DONE");
        result.put("trainStart", trainStart.toString());
        result.put("testStart", testStart.toString());
        result.put("testEnd", testEnd.toString());
        result.put("trainDays", windowDays);
        result.put("testDays", 1);
        result.put("evaluatedUsers", evaluated);
        result.put("skippedUsers", skipped);
        result.put("catalogSize", catalog.size());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("hitRate@10", round(hit10 / evaluated));
        metrics.put("hitRate@30", round(hit30 / evaluated));
        metrics.put("ndcg@10", round(ndcg10 / evaluated));
        metrics.put("ndcg@30", round(ndcg30 / evaluated));
        metrics.put("map@10", round(map10 / evaluated));
        metrics.put("auc", round(auc / evaluated));
        metrics.put("logLoss", round(logLoss / evaluated));
        metrics.put("coverage", round(recommended.size() / (double) Math.max(1, catalog.size())));
        metrics.put("novelty", round(novelty / evaluated));
        result.put("metrics", metrics);
        result.put("finishedAt", LocalDateTime.now().toString());
        archive((String) result.get("taskId"), result);
        return result;
    }

    public Map<String, Object> result(String taskId) {
        return redisOps.get(RedisKeys.recEvaluate(taskId)).map(raw -> {
            try {
                return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return Collections.<String, Object>emptyMap();
            }
        }).orElse(Collections.<String, Object>emptyMap());
    }

    // ------------------------------------------------------------------ 打分

    private Map<Long, Double> scoreForUser(List<Long> trainIds, List<Song> catalog, Map<Long, Song> byId) {
        Map<Long, Integer> artistCount = new HashMap<>();
        Map<String, Integer> genreCount = new HashMap<>();
        Map<String, Integer> languageCount = new HashMap<>();
        Set<Long> heard = new HashSet<>(trainIds);
        for (Long songId : trainIds) {
            Song song = byId.get(songId);
            if (song == null) {
                continue;
            }
            if (song.getArtistId() != null) {
                artistCount.merge(song.getArtistId(), 1, Integer::sum);
            }
            if (song.getGenre() != null) {
                genreCount.merge(song.getGenre(), 1, Integer::sum);
            }
            if (song.getLanguage() != null) {
                languageCount.merge(song.getLanguage(), 1, Integer::sum);
            }
        }
        int maxArtist = max(artistCount.values());
        int maxGenre = max(genreCount.values());
        int maxLanguage = max(languageCount.values());
        int maxHot = 1;
        for (Song song : catalog) {
            maxHot = Math.max(maxHot, song.getHotScore() == null ? 0 : song.getHotScore());
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Song song : catalog) {
            if (heard.contains(song.getId())) {
                continue;
            }
            double artist = song.getArtistId() == null ? 0
                    : artistCount.getOrDefault(song.getArtistId(), 0) / (double) maxArtist;
            double genre = song.getGenre() == null ? 0
                    : genreCount.getOrDefault(song.getGenre(), 0) / (double) maxGenre;
            double language = song.getLanguage() == null ? 0
                    : languageCount.getOrDefault(song.getLanguage(), 0) / (double) maxLanguage;
            double hot = (song.getHotScore() == null ? 0 : song.getHotScore()) / (double) maxHot;
            double freshness = Math.exp(-dataService.releaseDays(song) / 365.0);
            scores.put(song.getId(), 0.40 * artist + 0.25 * genre + 0.10 * language
                    + 0.15 * hot + 0.10 * freshness);
        }
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        Map<Long, Double> sorted = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    // ------------------------------------------------------------------ 指标

    private int hits(List<Long> top, Set<Long> test, int k) {
        int hits = 0;
        for (int i = 0; i < Math.min(k, top.size()); i++) {
            if (test.contains(top.get(i))) {
                hits++;
            }
        }
        return hits;
    }

    private double ndcg(List<Long> top, Set<Long> test, int k) {
        double dcg = 0;
        int limit = Math.min(k, top.size());
        for (int i = 0; i < limit; i++) {
            if (test.contains(top.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idcg = 0;
        int ideal = Math.min(k, test.size());
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double averagePrecision(List<Long> top, Set<Long> test, int k) {
        int hits = 0;
        double sum = 0;
        int limit = Math.min(k, top.size());
        for (int i = 0; i < limit; i++) {
            if (test.contains(top.get(i))) {
                hits++;
                sum += hits / (double) (i + 1);
            }
        }
        return hits == 0 ? 0 : sum / hits;
    }

    /**
     * AUC 与 LogLoss 在「Top50 + 固定哈希采样的负样本」上计算。
     * 全量负样本对排序指标没有额外信息，却会让 500 个用户 × 全曲库变得很慢。
     */
    /**
     * AUC 用秩和公式（Mann-Whitney U），LogLoss 用 min-max 归一到 (0,1) 的排序分作为概率。
     * 这不是校准过的 CTR 模型，指标间只做相对比较，后台展示时已注明口径。
     */
    private double[] aucAndLogLoss(Map<Long, Double> ranked, Set<Long> test) {
        List<Long> sample = new ArrayList<>();
        int index = 0;
        for (Long songId : ranked.keySet()) {
            if (index < TOP_K) {
                sample.add(songId);
            } else if ((Murmur3.hash32("eval|" + songId) & 15) == 0) {
                sample.add(songId); // 约 1/16 的负样本
            }
            index++;
        }
        List<Long> ordered = new ArrayList<>(sample);
        ordered.sort(Comparator.comparingDouble(ranked::get));
        double rankSumPositive = 0;
        long positives = 0;
        long negatives = 0;
        double min = ordered.isEmpty() ? 0 : ranked.get(ordered.get(0));
        double max = ordered.isEmpty() ? 0 : ranked.get(ordered.get(ordered.size() - 1));
        double range = Math.max(1e-9, max - min);
        double lossSum = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Long songId = ordered.get(i);
            boolean positive = test.contains(songId);
            if (positive) {
                rankSumPositive += i + 1;
                positives++;
            } else {
                negatives++;
            }
            double probability = Math.min(0.999, Math.max(0.001, (ranked.get(songId) - min) / range));
            lossSum += positive ? -Math.log(probability) : -Math.log(1 - probability);
        }
        double aucValue = 0.5;
        if (positives > 0 && negatives > 0) {
            aucValue = (rankSumPositive - positives * (positives + 1) / 2.0) / (positives * (double) negatives);
        }
        double loss = lossSum / Math.max(1, ordered.size());
        return new double[]{Math.max(0, Math.min(1, aucValue)), loss};
    }

    private double novelty(List<Long> top, Map<Long, Integer> popularityRank, int catalogSize) {
        if (top.isEmpty() || catalogSize <= 1) {
            return 0;
        }
        double sum = 0;
        int counted = 0;
        for (Long songId : top) {
            Integer rank = popularityRank.get(songId);
            if (rank != null) {
                sum += 1.0 - rank / (double) catalogSize;
                counted++;
            }
        }
        return counted == 0 ? 0 : sum / counted;
    }

    // ------------------------------------------------------------------ 工具

    private void archive(String taskId, Map<String, Object> result) {
        try {
            redisOps.set(RedisKeys.recEvaluate(taskId),
                    objectMapper.writeValueAsString(result), RESULT_TTL);
        } catch (Exception e) {
            log.warn("离线评估结果归档失败 taskId={} cause={}", taskId, e.toString());
        }
    }

    private String taskId(int trainDays, int maxUsers) {
        String seed = "offline|" + LocalDate.now().format(DATE) + "|" + trainDays + "|" + maxUsers;
        return "eval-" + LocalDate.now().format(DATE) + "-" + Integer.toHexString(Murmur3.hash32(seed));
    }

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private int max(java.util.Collection<Integer> values) {
        int max = 1;
        for (Integer value : values) {
            if (value != null) {
                max = Math.max(max, value);
            }
        }
        return max;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
