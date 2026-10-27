package com.example.newmusic.module.recommend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 推荐反馈 t_recommend_feedback。
 *
 * <p>刻意设计为**追加日志**：每次喜欢/不喜欢/撤销都插入一行，当前屏蔽态由
 * {@code FeedbackService} 按时间顺序回放得出。这样撤销不需要物理删除或覆盖历史，
 * 也天然满足 PRD 17 BR-17-019「反馈必须上报埋点」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recommend_feedback")
@ApiModel("推荐反馈")
public class RecommendFeedback extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final String LIKE = "LIKE";
    public static final String DISLIKE = "DISLIKE";
    public static final String DISLIKE_ARTIST = "DISLIKE_ARTIST";
    public static final String UNDO = "UNDO";
    public static final String UNDO_ARTIST = "UNDO_ARTIST";

    private Long userId;
    private Long songId;
    private Long artistId;

    @ApiModelProperty("LIKE/DISLIKE/DISLIKE_ARTIST/UNDO/UNDO_ARTIST")
    private String action;

    @ApiModelProperty("DAILY/GUESS/SIMILAR/HOME")
    private String scene;

    private Integer position;
}
