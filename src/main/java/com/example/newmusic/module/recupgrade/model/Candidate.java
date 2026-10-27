package com.example.newmusic.module.recupgrade.model;

import com.example.newmusic.module.music.entity.Song;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 贯穿召回 → 融合 → 粗排 → 精排 → 重排的候选。
 *
 * <p>召回阶段只填 {@code songId} 与来源；融合阶段填分数；排序阶段填特征；
 * 最终阶段才回填 {@link Song}（禁止在召回阶段逐首查详情）。
 */
@Data
public class Candidate {

    private Long songId;

    /** 融合分数（WRF） */
    private double fusedScore;
    /** 来源 → 该来源贡献的归一化分数，键按加入顺序保留，便于解释 */
    private Map<String, Double> sources = new LinkedHashMap<>();
    /** 多源共识：被几个源召回 */
    private int sourceCount;

    private double coarseScore;
    private double fineScore;
    private double pctr;
    private double pcvr;
    private double plike;
    private double pskip;

    // ---- 粗排/精排特征（归一化到 [0,1] 或原始统计） ----
    private double hotFeature;
    private double tagMatch;
    private double artistAffinity;
    private double freshness;
    private double completionPrior;
    private double skipPrior;

    // ---- 用于重排的静态属性 ----
    private Long artistId;
    private Long albumId;
    private String genre;
    private String language;
    private int hotScore;
    private int releaseDays = 3650;
    private boolean longTail;
    private boolean newSong;
    private boolean editor;
    private boolean explored;
    private double exploreWeight;

    private Song song;
    private String reason;

    /** 精排不可用时的降级标记（BR-24-027）。 */
    private String scoreMode = "MODEL";

    public void addSource(String source, double contribution) {
        sources.merge(source, contribution, Double::sum);
        sourceCount = sources.size();
    }
}
