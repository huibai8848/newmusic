package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品协同召回：取用户最近听过的 3 首歌，各自展开相似歌曲，按「最近程度」加权合并。
 * 与 vector_song 共用同一张相似表，区别在于这里聚合多首种子，覆盖更宽。
 */
@Component
public class CfItemRecallSource extends AbstractRecallSource {

    public CfItemRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "cf_item";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser() && stageAtLeast(ctx, "S2");
    }

    @Override
    public int timeoutMs() {
        return 100;
    }

    @Override
    public int priority() {
        return 85;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        Map<Long, Double> scores = new LinkedHashMap<>();
        List<Long> seeds = ctx.getRecentSongIds();
        int used = 0;
        for (Long seed : seeds) {
            if (used >= 3) {
                break;
            }
            used++;
            double seedWeight = 1.0 / used;
            for (Long songId : data.similarSongs(seed, 100)) {
                scores.merge(songId, seedWeight, Math::max);
            }
        }
        List<Long> ids = new java.util.ArrayList<>(scores.keySet());
        return RecallResult.ok(name(), collect(ctx, ids, scores), System.currentTimeMillis() - start);
    }
}
