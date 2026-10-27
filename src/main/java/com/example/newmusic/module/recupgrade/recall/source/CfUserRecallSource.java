package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 用户协同召回：与我听过的歌在同一批用户历史中共现的歌（item-based CF 的用户侧聚合）。
 * 需要 S2 及以上阶段——S0/S1 历史太短，共现矩阵全是噪声。
 */
@Component
public class CfUserRecallSource extends AbstractRecallSource {

    public CfUserRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "cf_user";
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
        return 90;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(),
                collect(ctx, data.coOccurrenceSongs(ctx.getUserId(), ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
