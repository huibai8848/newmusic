package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 用户活跃时段聚合。 */
@Data
public class HourAggRow {
    private Long userId;
    private Integer hour;
    private Long cnt;
}
