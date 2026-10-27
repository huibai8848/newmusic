package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recommend.service.ScoringService;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像向量召回（Milvus 不可用时的替代）：用画像对全量可播歌曲打分，取 Top-N。
 *
 * <p>真实系统在这里是 ANN 检索；本实现是 O(曲库) 的加权打分。曲库上万时应换成
 * 离线 embedding + 在线近邻，但接口契约保持不变。
 */
@Component
public class VectorRecallSource extends AbstractRecallSource {

    private final ScoringService scoringService;

    public VectorRecallSource(RecallDataService data, ScoringService scoringService) {
        super(data);
        this.scoringService = scoringService;
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser() && ctx.getProfile() != null && stageAtLeast(ctx, "S2");
    }

    @Override
    public int timeoutMs() {
        return 80;
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<ScoringService.ScoredSong> ranked = scoringService.scoreAll(data.playableSongs(), ctx.getProfile());
        int limit = Math.min(ranked.size(), ctx.getLimit() * 2);
        List<Long> ids = new ArrayList<>(limit);
        Map<Long, Double> scores = new LinkedHashMap<>();
        double max = ranked.isEmpty() ? 1d : Math.max(0.0001, ranked.get(0).getScore());
        for (int i = 0; i < limit; i++) {
            ScoringService.ScoredSong scored = ranked.get(i);
            ids.add(scored.getSong().getId());
            scores.put(scored.getSong().getId(), scored.getScore() / max);
        }
        return RecallResult.ok(name(), collect(ctx, ids, scores), System.currentTimeMillis() - start);
    }
}
