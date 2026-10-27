package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 本地召回。真实系统按用户常驻城市召回本地音乐人/本地热歌；本机用户表没有地区字段，
 * 因此退化为「标签含 本地/城市 的公开歌单」。没有这类歌单时返回空结果（这是成功，不是失败）。
 */
@Component
public class LocalRecallSource extends AbstractRecallSource {

    public LocalRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public int timeoutMs() {
        return 60;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(), collect(ctx, data.localSongs(ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
