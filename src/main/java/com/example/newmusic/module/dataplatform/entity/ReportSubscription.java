package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 报表订阅（BR-26-085）。 */
@Data
@TableName("t_report_subscription")
public class ReportSubscription {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long adminId;
    /** 逗号分隔的报表类型 */
    private String reportTypes;
    private String frequency;
    private String channel;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
