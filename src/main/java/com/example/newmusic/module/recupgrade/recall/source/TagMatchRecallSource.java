package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

/**
 * 标签召回：取画像中权重最高的 5 个标签，召回带这些标签的歌。
 * S1 起启用——只要有 1 次播放就能形成初步标签偏好。
 */
@Component
public class TagMatchRecallSource extends AbstractRecallSource {

    public TagMatchRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "tag_match";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser() && ctx.getProfile() != null && stageAtLeast(ctx, "S1");
    }

    @Override
    public int timeoutMs() {
        return 80;
    }

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        return RecallResult.ok(name(), collect(ctx,
                data.tagMatchedSongs(ctx.getProfile().getTagWeights(), ctx.getLimit() * 2, 5)),
                System.currentTimeMillis() - start);
    }
}
