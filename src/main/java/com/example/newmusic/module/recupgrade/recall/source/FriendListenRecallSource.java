package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/** 好友在听召回：关注的人最近 30 天播放最多的歌。 */
@Component
public class FriendListenRecallSource extends AbstractRecallSource {

    public FriendListenRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "friend_listen";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser();
    }

    @Override
    public int timeoutMs() {
        return 120;
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(),
                collect(ctx, data.friendListeningSongs(ctx.getUserId(), ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
