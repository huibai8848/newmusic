package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 单曲相似召回：种子歌优先取请求里的 {@code seedSongId}，否则取最近播放的第一首。
 * 它支撑「以歌找歌」与歌曲详情页的相似推荐。
 */
@Component
public class VectorSongRecallSource extends AbstractRecallSource {

    public VectorSongRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "vector_song";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.getSeedSongId() != null
                || (ctx.hasUser() && ctx.getRecentSongIds() != null && !ctx.getRecentSongIds().isEmpty());
    }

    @Override
    public int timeoutMs() {
        return 100;
    }

    @Override
    public int priority() {
        return 75;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        Long seed = ctx.getSeedSongId();
        if (seed == null && ctx.getRecentSongIds() != null && !ctx.getRecentSongIds().isEmpty()) {
            seed = ctx.getRecentSongIds().get(0);
        }
        return RecallResult.ok(name(), collect(ctx, data.similarSongs(seed, ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
