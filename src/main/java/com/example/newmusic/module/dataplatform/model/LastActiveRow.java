package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;

/** 用户最近一次上报日期（生命周期判断用）。 */
@Data
public class LastActiveRow {
    private Long userId;
    private LocalDate lastDt;
}
