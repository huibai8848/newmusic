package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;

/** 用户单日行为画像（留存分群用）。 */
@Data
public class RetentionFactRow {
    private Long userId;
    private LocalDate dt;
    private Long plays;
    private Long social;
    private Long room;
    private Long ai;
}
