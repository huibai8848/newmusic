package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 指标口径变更记录（BR-26-028：口径变更必须审批且保留历史）。 */
@Data
@TableName("t_metric_change_log")
public class MetricChangeLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String metricId;
    private String oldDefinition;
    private String newDefinition;
    private String reason;
    private Long applicantId;
    private String approver;
    /** 0 待审批 / 1 已通过 / 2 已驳回 */
    private Integer status;
    private LocalDate effectiveDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
