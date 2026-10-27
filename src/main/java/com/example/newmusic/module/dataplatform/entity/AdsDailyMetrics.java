package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** ADS 日指标（直接服务看板与报表）。 */
@Data
@TableName("t_ads_daily_metrics")
public class AdsDailyMetrics {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate statDate;
    private String metricId;
    private String metricName;
    private BigDecimal metricValue;
    private String dimension;
    private String dimensionValue;
    private String extra;
    private LocalDateTime createTime;
}
