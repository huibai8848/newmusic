package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 指标快照（趋势与验收用）。 */
@Data
@TableName("t_ops_metric_snapshot")
public class MetricSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String metricName;
    private Double metricValue;
    private String tags;
    private LocalDateTime snapshotTime;
}
