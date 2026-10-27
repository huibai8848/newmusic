package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;

/** 按天聚合值。 */
@Data
public class DayValueRow {
    private LocalDate dt;
    private Long v;
}
