package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;

/** 用户某日有行为。 */
@Data
public class RetentionDayRow {
    private Long userId;
    private LocalDate dt;
}
