package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/** 关注歌手召回：用户关注的歌手名下可播放歌曲。S1 起启用，游客不适用。 */
@Component
public class FollowArtistRecallSource extends AbstractRecallSource {

    public FollowArtistRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "follow_artist";
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
        return 65;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(),
                collect(ctx, data.artistSongs(data.followedArtistIds(ctx.getUserId()), ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
