package com.example.newmusic.module.ai.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Size;
import java.util.List;

/** 运营手工修正情绪标注。保存后 {@code source} 变为 MANUAL，批处理不再覆盖。 */
@Data
public class EmotionUpdateReq {

    @DecimalMin(value = "0.0", message = "valence 取值 0~1")
    @DecimalMax(value = "1.0", message = "valence 取值 0~1")
    private Double valence;

    @DecimalMin(value = "0.0", message = "arousal 取值 0~1")
    @DecimalMax(value = "1.0", message = "arousal 取值 0~1")
    private Double arousal;

    @DecimalMin(value = "0.0", message = "energy 取值 0~1")
    @DecimalMax(value = "1.0", message = "energy 取值 0~1")
    private Double energy;

    @Size(max = 3, message = "情绪标签最多 3 个")
    private List<String> emotionTags;

    @Size(max = 3, message = "场景标签最多 3 个")
    private List<String> sceneTags;

    @Size(max = 200, message = "理由不能超过 200 字")
    private String reason;
}
