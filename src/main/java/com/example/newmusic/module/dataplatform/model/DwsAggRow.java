package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 用户近 30 天 DWS 汇总。 */
@Data
public class DwsAggRow {
    private Long userId;
    private Long playCnt;
    private Long validPlayCnt;
    private Long playDuration;
    private Long interactCnt;
    private Long roomCnt;
    private Long activeDays;
}
