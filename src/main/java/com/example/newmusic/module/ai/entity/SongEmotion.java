package com.example.newmusic.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 歌曲情绪 / 场景标注（FR-23-004）。
 *
 * <p>{@code source=MANUAL} 的记录**任何自动任务都不得覆盖**（BR-23-040）：
 * 运营手工改过的标签代表业务判断，批处理把它冲掉之后就再也找不回来了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_song_emotion")
public class SongEmotion extends BaseEntity {

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_MANUAL = "MANUAL";

    private Long songId;
    private BigDecimal valence;
    private BigDecimal arousal;
    private BigDecimal energy;
    /** JSON 数组字符串，如 {@code ["快乐","浪漫"]} */
    private String emotionTags;
    private String sceneTags;
    private BigDecimal confidence;
    private String source;
    private String reason;
    private String modelVersion;
    private String promptVersion;
}
