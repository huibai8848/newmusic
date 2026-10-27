package com.example.newmusic.module.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 歌曲情绪 / 场景标注（C 端展示 + 后台质检共用）。 */
@Data
@Builder
public class SongEmotionVO {

    private Long songId;
    private String songName;

    /** 是否已标注。false 时各数值是中性默认值，前端不应展示为真实结果 */
    private boolean annotated;

    private double valence;
    private double arousal;
    private double energy;
    private List<String> emotionTags;
    private List<String> sceneTags;
    private double confidence;

    /** AI / MANUAL */
    private String source;
    private String reason;
    private String modelVersion;
    private String promptVersion;
    private boolean lowConfidence;

    private LocalDateTime updatedAt;
}
