package com.example.newmusic.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 标注失败重试队列（BR-23-081）。同一 {@code scene + bizId} 只有一行，重试时累加次数。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_annotation_fail")
public class AiAnnotationFail extends BaseEntity {

    private String scene;
    private String bizId;
    private Integer attemptCount;
    private String lastError;
    private LocalDateTime nextRetryAt;
}
