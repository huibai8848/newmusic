package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** POST /api/v1/admin/chaos/experiments */
@Data
public class ChaosStartReq {

    @NotBlank(message = "experimentName 不能为空")
    @Size(max = 100, message = "experimentName 不能超过 100 字")
    private String experimentName;

    /** REDIS_LIMITER_UNAVAILABLE / AI_TIMEOUT / SLOW_DB */
    @NotBlank(message = "faultType 不能为空")
    private String faultType;

    private String target = "SERVICE";

    @Min(value = 1, message = "爆炸半径不能小于 1%")
    @Max(value = 100, message = "爆炸半径不能超过 100%")
    private Integer blastRadius = 10;

    @Min(value = 1, message = "时长不能小于 1 秒")
    private Integer durationSeconds = 30;

    @Size(max = 500, message = "hypothesis 不能超过 500 字")
    private String hypothesis;
}
