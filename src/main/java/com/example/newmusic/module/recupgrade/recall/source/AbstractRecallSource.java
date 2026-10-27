package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallItem;
import com.example.newmusic.module.recupgrade.model.RecallSource;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 召回源的公共骨架：统一做排除、截断与分数归一化。
 *
 * <p>排除集（已听/不喜欢/屏蔽歌手/已下架）必须在**每个源**生效，而不是只在融合阶段做一次：
 * 一个源如果先取 300 首、其中 200 首被排除，融合时它就等于只剩 100 首，
 * 权重会被动缩水（BR-24-006）。放在这里还有一个好处——各源只写「怎么取 ID」。
 */
public abstract class AbstractRecallSource implements RecallSource {

    protected final RecallDataService data;

    protected AbstractRecallSource(RecallDataService data) {
        this.data = data;
    }

    /**
     * 按输入顺序（即源内排名）构造结果。
     *
     * @param rawScores songId → 源内原始分；缺失时按名次给衰减分
     */
    protected List<RecallItem> collect(RecallContext ctx, List<Long> ids, Map<Long, Double> rawScores) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = ctx.getLimit() > 0 ? ctx.getLimit() : 300;
        Map<Long, Song> playable = data.playableMap();
        Set<Long> excludeIds = ctx.getExcludeIds() == null ? Collections.emptySet() : ctx.getExcludeIds();
        Set<Long> excludeArtists = ctx.getExcludeArtists() == null ? Collections.emptySet() : ctx.getExcludeArtists();
        LinkedHashSet<Long> picked = new LinkedHashSet<>();
        List<RecallItem> result = new ArrayList<>(Math.min(limit, ids.size()));
        int rank = 0;
        for (Long songId : ids) {
            if (songId == null || picked.contains(songId) || excludeIds.contains(songId)) {
                continue;
            }
            Song song = playable.get(songId);
            if (song == null) {
                // 不可播放的歌曲不进候选池（BR-24-006 / EX-24-001-3）
                continue;
            }
            if (song.getArtistId() != null && excludeArtists.contains(song.getArtistId())) {
                continue;
            }
            picked.add(songId);
            double score = rawScores != null && rawScores.get(songId) != null
                    ? rawScores.get(songId)
                    : Math.max(0.01, 1.0 - rank / (double) limit);
            RecallItem item = RecallItem.of(songId, score);
            item.getExtra().put("rank", rank);
            item.getExtra().put("source", name());
            result.add(item);
            rank++;
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    protected List<RecallItem> collect(RecallContext ctx, List<Long> ids) {
        return collect(ctx, ids, null);
    }

    /** 冷启动阶段比较：S0 &lt; S1 &lt; S2 &lt; S3（字符串字典序恰好一致）。 */
    protected boolean stageAtLeast(RecallContext ctx, String stage) {
        String current = ctx.getColdStartStage() == null ? "S3" : ctx.getColdStartStage();
        return current.compareTo(stage) >= 0;
    }

    /** 默认超时 100ms（PRD §FR-24-001）；个别慢源可覆盖。 */
    @Override
    public int timeoutMs() {
        return 100;
    }

    @Override
    public int priority() {
        return 0;
    }
}
