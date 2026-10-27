package com.example.newmusic.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 歌词翻译质量反馈（BR-23-024）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_feedback")
public class AiFeedback extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;

    private String scene;
    private Long songId;
    private Long userId;
    private String issue;
    private Integer status;
}
