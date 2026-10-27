package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
@ApiModel("启用/禁用管理员")
public class AdminAccountStatusReq {

    @Min(value = 1, message = "状态只能为 1 或 2")
    @Max(value = 2, message = "状态只能为 1 或 2")
    private Integer status;
}
