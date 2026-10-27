package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长尾召回：播放量处于曲库后 50%、热度不高的歌。
 * 它是长尾占比指标（≥10%）的主要供给；没有它，重排阶段的长尾插入会无米下锅。
 */
@Component
public class LongTailRecallSource extends AbstractRecallSource {

    public LongTailRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "long_tail";
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
        return 80;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<Song> songs = data.longTailSongs(ctx.getLimit() * 2);
        List<Long> ids = new ArrayList<>(songs.size());
        for (Song song : songs) {
            ids.add(song.getId());
        }
        return RecallResult.ok(name(), collect(ctx, ids), System.currentTimeMillis() - start);
    }
}
