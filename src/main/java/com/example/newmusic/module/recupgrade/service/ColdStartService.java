package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import org.springframework.stereotype.Service;

/**
 * 冷启动分层（FR-24-006）。
 *
 * <table>
 *   <tr><th>阶段</th><th>行为数</th><th>可用召回</th></tr>
 *   <tr><td>S0</td><td>0</td><td>热门 Top200</td></tr>
 *   <tr><td>S1</td><td>1–5</td><td>标签 + 热门 + 关注</td></tr>
 *   <tr><td>S2</td><td>6–30</td><td>协同 + 向量 + 标签</td></tr>
 *   <tr><td>S3</td><td>&gt;30</td><td>全部</td></tr>
 * </table>
 *
 * <p>阶段只决定「开哪些源」和候选上限，不改变排序与重排规则——否则冷启动会变成
 * 一套需要单独测试的平行链路。
 */
@Service
public class ColdStartService {

    private final RecallDataService dataService;

    public ColdStartService(RecallDataService dataService) {
        this.dataService = dataService;
    }

    public String stage(long behaviorCount) {
        return dataService.coldStartStage(behaviorCount);
    }

    public void apply(RecallContext ctx) {
        long behaviorCount = ctx.getProfile() == null ? 0 : ctx.getProfile().getBehaviorCount();
        String stage = stage(behaviorCount);
        ctx.setColdStartStage(stage);
        ctx.setColdStart(behaviorCount < 5);
        ctx.getFeatures().put("coldStartStage", stage);
        ctx.getFeatures().put("behaviorCount", behaviorCount);
    }

    /** S0 候选上限 200；其余阶段不额外限制（融合阶段已有 500 上限）。 */
    public int candidateLimit(String stage) {
        return "S0".equals(stage) ? 200 : 500;
    }

    /** 冷启动解释文案：S0/S1 的推荐理由不能假装「因为你常听」。 */
    public String reason(String stage) {
        if (stage == null) {
            return "为你推荐";
        }
        switch (stage) {
            case "S0":
                return "大家都在听";
            case "S1":
                return "根据你最近的喜好";
            case "S2":
                return "和你口味相近的人也在听";
            default:
                return "根据你的收听习惯";
        }
    }
}
