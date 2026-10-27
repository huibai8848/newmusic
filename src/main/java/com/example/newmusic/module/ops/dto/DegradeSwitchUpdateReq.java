package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/** PUT /api/v1/admin/degrade-switches/{key} */
@Data
public class DegradeSwitchUpdateReq {

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    private String remark;
}
