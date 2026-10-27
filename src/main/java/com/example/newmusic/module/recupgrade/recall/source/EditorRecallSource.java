package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 编辑精选召回：运营维护的 type=2 歌单。融合权重 2.0、不衰减，
 * 重排阶段还会把其中最好的几首钉在固定位置（PIN 算子）。
 */
@Component
public class EditorRecallSource extends AbstractRecallSource {

    public EditorRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "editor";
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public int timeoutMs() {
        return 50;
    }

    @Override
    public int priority() {
        return 95;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(), collect(ctx, data.editorSongs(ctx.getLimit() * 2)),
                System.currentTimeMillis() - start);
    }
}
