package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 粗排（FR-24-003）：约 20 个轻量特征线性加权，500 → 150，预算 30ms。
 *
 * <p>粗排只用「已经在内存里」的特征——融合分数、歌单/歌曲属性、用户画像。
 * 需要访问 Redis 或多次查库的重特征一律留给精排，否则 30ms 预算守不住。
 */
@Slf4j
@Service
public class CoarseRanker {

    private final RecConfigService configService;
    private final RecallDataService dataService;

    public CoarseRanker(RecConfigService configService, RecallDataService dataService) {
        this.configService = configService;
        this.dataService = dataService;
    }

    public List<Candidate> rank(String scene, List<Candidate> candidates, RecallContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        RecConfigService.RankSetting setting = configService.rank(scene);
        RecConfigService.CoarseSetting coarse = setting == null || setting.getCoarse() == null
                ? new RecConfigService.CoarseSetting() : setting.getCoarse();
        Map<String, Double> weights = coarse.getWeights();
        int target = coarse.getTarget() > 0 ? coarse.getTarget() : 150;
        int skipBelow = coarse.getSkipBelow() > 0 ? coarse.getSkipBelow() : 30;

        // EX-24-003-7：候选太少时粗排没有筛选意义，直接全部进入精排
        if (candidates.size() <= skipBelow) {
            for (Candidate candidate : candidates) {
                fillFeatures(candidate, ctx);
                candidate.setCoarseScore(candidate.getFusedScore());
            }
            return new ArrayList<>(candidates);
        }

        double maxFused = 0.0001;
        for (Candidate candidate : candidates) {
            maxFused = Math.max(maxFused, candidate.getFusedScore());
        }
        Map<Long, PlayHistoryMapper.SongStatRow> stats = dataService.songStats(ctx.getUserId());
        for (Candidate candidate : candidates) {
            fillFeatures(candidate, ctx);
            applySkipPrior(candidate, stats.get(candidate.getSongId()));
            double recall = clamp(candidate.getFusedScore() / maxFused);
            double hot = clamp(candidate.getHotScore() / 1000.0);
            double score = weight(weights, "recall", 0.30) * recall
                    + weight(weights, "hot", 0.15) * hot
                    + weight(weights, "tag", 0.15) * candidate.getTagMatch()
                    + weight(weights, "artist", 0.10) * candidate.getArtistAffinity()
                    + weight(weights, "freshness", 0.10) * candidate.getFreshness()
                    + weight(weights, "completion", 0.08) * candidate.getCompletionPrior()
                    + weight(weights, "skip", 0.07) * (1.0 - candidate.getSkipPrior());
            candidate.setCoarseScore(clamp(score));
        }

        candidates.sort(Comparator.comparingDouble(Candidate::getCoarseScore).reversed()
                .thenComparing(Candidate::getSongId));
        if (candidates.size() <= target) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, target));
    }

    /** 把粗排需要的特征写回候选，精排可以直接复用，省一次重复计算。 */
    private void fillFeatures(Candidate candidate, RecallContext ctx) {
        Song song = candidate.getSong();
        if (candidate.getArtistId() == null && song != null) {
            candidate.setArtistId(song.getArtistId());
        }
        if (candidate.getHotScore() <= 0 && song != null && song.getHotScore() != null) {
            candidate.setHotScore(song.getHotScore());
        }
        candidate.setHotFeature(clamp(candidate.getHotScore() / 1000.0));
        candidate.setFreshness(Math.exp(-Math.max(0, candidate.getReleaseDays()) / 180.0));
        if (candidate.getCompletionPrior() <= 0) {
            candidate.setCompletionPrior(song != null && song.getCompletionRate() != null
                    ? clamp(song.getCompletionRate().doubleValue()) : 0.5);
        }
        if (candidate.getSkipPrior() <= 0) {
            candidate.setSkipPrior(clamp(1.0 - candidate.getCompletionPrior()));
        }

        if (ctx.getProfile() == null) {
            candidate.setTagMatch(0.5);
            candidate.setArtistAffinity(0.5);
            return;
        }
        Map<Long, Double> tagWeights = ctx.getProfile().getTagWeights();
        Set<Long> tags = dataService.tagsOf(candidate.getSongId());
        if (tags.isEmpty() || tagWeights.isEmpty()) {
            candidate.setTagMatch(0.3);
        } else {
            double sum = 0;
            for (Long tag : tags) {
                sum += tagWeights.getOrDefault(tag, 0.0);
            }
            candidate.setTagMatch(clamp(sum / tags.size()));
        }
        if (candidate.getArtistId() == null) {
            candidate.setArtistAffinity(0.3);
        } else {
            candidate.setArtistAffinity(clamp(
                    ctx.getProfile().getArtistWeights().getOrDefault(candidate.getArtistId(), 0.0)));
        }
    }

    /** 用用户自己的播放记录修正跳过率先验（比全站完成率更贴近个人）。 */
    private void applySkipPrior(Candidate candidate, PlayHistoryMapper.SongStatRow stat) {
        if (stat == null || stat.getCoCount() == null || stat.getCoCount() <= 0) {
            return;
        }
        Double avgRatio = stat.getAvgRatio();
        if (avgRatio == null) {
            return;
        }
        double userSkip = clamp(1.0 - avgRatio);
        // 个人行为与全站先验各占一半：样本少时不能完全相信个人值
        candidate.setSkipPrior(clamp(0.5 * candidate.getSkipPrior() + 0.5 * userSkip));
    }

    private double weight(Map<String, Double> weights, String key, double fallback) {
        if (weights == null || weights.get(key) == null) {
            return fallback;
        }
        return weights.get(key);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
