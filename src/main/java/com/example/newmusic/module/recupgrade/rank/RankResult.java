package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.module.recupgrade.model.Candidate;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次精排的结果与元信息（模型版本、降级模式、耗时都要进推荐日志）。 */
@Data
public class RankResult {

    private List<Candidate> candidates = new ArrayList<>();
    private String modelVersion = "rule-lr-v1";
    private String mode = "MODEL";
    private long costMs;

    public static RankResult of(List<Candidate> candidates, String modelVersion, String mode, long costMs) {
        RankResult result = new RankResult();
        result.candidates = candidates == null ? new ArrayList<Candidate>() : candidates;
        result.modelVersion = modelVersion;
        result.mode = mode;
        result.costMs = costMs;
        return result;
    }
}
