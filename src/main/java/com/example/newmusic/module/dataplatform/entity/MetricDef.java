package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 指标定义（PRD 26 FR-26-003）。 */
@Data
@TableName("t_metric_def")
public class MetricDef {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String metricId;
    private String metricName;
    private String category;
    /** 唯一口径定义 */
    private String definition;
    private String calculation;
    private String dataSource;
    /** 可用维度 JSON */
    private String dimensions;
    private String updateFreq;
    private String owner;
    /** 口径版本，如 v1.0；与乐观锁无关 */
    private String version;
    /** 0 草稿 / 1 生效 / 2 废弃 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
