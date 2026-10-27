package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 画像计算用的用户基础行。 */
@Data
public class ProfileUserRow {
    private Long userId;
    private Integer gender;
    private LocalDate birthday;
    private Integer vipLevel;
    private LocalDateTime createTime;
    private String region;
    private Integer followCount;
    private Integer fansCount;
}
