package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新歌召回：发布 7 天内的歌。曝光受新歌保护策略约束（FR-24-006），
 * 这里的限制由 {@code NewSongService} 在重排阶段落实，召回本身不截断。
 */
@Component
public class NewSongRecallSource extends AbstractRecallSource {

    public NewSongRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "new_song";
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return stageAtLeast(ctx, "S1");
    }

    @Override
    public int timeoutMs() {
        return 60;
    }

    @Override
    public int priority() {
        return 55;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<Song> songs = data.newSongs(ctx.getLimit() * 2);
        List<Long> ids = new ArrayList<>(songs.size());
        Map<Long, Double> scores = new LinkedHashMap<>();
        int index = 0;
        for (Song song : songs) {
            ids.add(song.getId());
            scores.put(song.getId(), 1.0 - 0.5 * (index++ / (double) Math.max(1, songs.size())));
        }
        return RecallResult.ok(name(), collect(ctx, ids, scores), System.currentTimeMillis() - start);
    }
}
