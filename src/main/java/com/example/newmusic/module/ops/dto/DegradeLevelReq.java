package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/** POST /api/v1/admin/degrade/level */
@Data
public class DegradeLevelReq {

    @NotNull(message = "level 不能为空")
    @Min(value = 0, message = "等级不能小于 0")
    @Max(value = 5, message = "等级不能大于 5")
    private Integer level;

    private String reason;
}
