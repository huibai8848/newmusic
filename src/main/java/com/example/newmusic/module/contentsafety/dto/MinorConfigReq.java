package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
@ApiModel("未成年人保护配置")
public class MinorConfigReq {

    private Boolean isMinor;
    private Integer dailyLimitMinutes;

    @Min(1)
    @Max(24)
    private Integer disableStartHour;

    @Min(0)
    @Max(23)
    private Integer disableEndHour;

    private Long guardianUserId;
    private Boolean mode;
}
