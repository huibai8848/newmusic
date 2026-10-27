package com.example.newmusic.module.ops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 降级开关 + 当前等级下的运行态。 */
@Data
public class DegradeSwitchVO {

    private String switchKey;
    private String switchName;
    private Integer enabled;
    private Integer degradeLevel;
    private String description;
    private Long operatorId;
    private Integer version;
    private LocalDateTime updateTime;
    /** 当前等级下该开关是否已关闭（enabled=0）。 */
    private Boolean activeAtCurrentLevel;
    private Integer currentLevel;
}
