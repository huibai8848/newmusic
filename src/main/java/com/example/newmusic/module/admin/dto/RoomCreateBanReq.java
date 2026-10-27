package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("禁止房主创建房间")
public class RoomCreateBanReq {

    @NotBlank(message = "请填写原因")
    @Size(min = 5, max = 500, message = "原因需为 5-500 个字符")
    private String reason;

    /** 单位：小时；0 或空表示永久 */
    private Integer hours;
}
