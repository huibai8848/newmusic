package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;

/** 用户首次活跃日期。 */
@Data
public class FirstActiveRow {
    private Long userId;
    private LocalDate firstDt;
}
