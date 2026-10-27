package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallItem;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热门召回：全站热度榜。游客与冷启动 S0 唯一必开的源，也是兜底池的来源。
 */
@Component
public class HotRecallSource extends AbstractRecallSource {

    public HotRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "hot";
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public int timeoutMs() {
        return 80;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<Song> songs = data.playableSongs();
        int take = Math.min(songs.size(), Math.max(ctx.getLimit() * 2, ctx.getLimit()));
        List<Long> ids = new ArrayList<>(take);
        Map<Long, Double> scores = new LinkedHashMap<>();
        int maxHot = 1;
        for (Song song : songs) {
            maxHot = Math.max(maxHot, song.getHotScore() == null ? 0 : song.getHotScore());
        }
        for (int i = 0; i < take; i++) {
            Song song = songs.get(i);
            ids.add(song.getId());
            scores.put(song.getId(), (song.getHotScore() == null ? 0 : song.getHotScore()) / (double) maxHot);
        }
        List<RecallItem> items = collect(ctx, ids, scores);
        return RecallResult.ok(name(), items, System.currentTimeMillis() - start);
    }
}
