package com.example.newmusic.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 调用日志（BR-23-003）。
 *
 * <p>不继承 {@code BaseEntity}：日志表没有 update_by/deleted/version，
 * 也不应该被逻辑删除——成本审计数据必须只增不改。
 */
@Data
@TableName("t_ai_call_log")
public class AiCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String scene;
    private String model;
    private String modelLevel;
    private String promptVersion;
    private Long userId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer costCent;
    private Integer latencyMs;
    private Integer fromCache;
    private Integer success;
    private Integer degraded;
    private String errorMsg;
    private String bizId;
    private String traceId;
    private LocalDateTime createTime;
}
