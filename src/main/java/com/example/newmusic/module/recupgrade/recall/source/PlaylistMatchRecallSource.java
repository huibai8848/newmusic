package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 歌单召回：优先用户收藏的歌单；没有收藏时按画像第一流派匹配公开歌单。
 * 这条源承担「场景化收听」的供给，例如「深夜书房」「通勤路上」。
 */
@Component
public class PlaylistMatchRecallSource extends AbstractRecallSource {

    public PlaylistMatchRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "playlist_match";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser();
    }

    @Override
    public int timeoutMs() {
        return 100;
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<Long> ids = data.collectedPlaylistSongs(ctx.getUserId(), ctx.getLimit() * 2);
        if (ids.isEmpty() && ctx.getProfile() != null) {
            String genre = null;
            Map<String, Double> genres = ctx.getProfile().getGenreWeights();
            if (!genres.isEmpty()) {
                genre = genres.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);
            }
            ids = data.publicPlaylistSongs(genre, ctx.getLimit() * 2);
        }
        if (ids == null) {
            ids = Collections.emptyList();
        }
        return RecallResult.ok(name(), collect(ctx, ids), System.currentTimeMillis() - start);
    }
}
