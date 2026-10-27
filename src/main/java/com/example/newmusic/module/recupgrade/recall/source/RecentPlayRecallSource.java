package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 最近播放召回：最近 100 首里按时间倒序，供「再听一次」。已听排除集不会误伤它——
 * 引擎在构造上下文时只把「明确不喜欢」放进排除集，最近播放本身不清除。
 */
@Component
public class RecentPlayRecallSource extends AbstractRecallSource {

    public RecentPlayRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "recent_play";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser() && stageAtLeast(ctx, "S1");
    }

    @Override
    public int timeoutMs() {
        return 60;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(),
                collect(ctx, data.recentSongIds(ctx.getUserId(), ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
