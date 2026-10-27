package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("强制关闭房间")
public class ForceCloseRoomReq {

    @NotBlank(message = "请填写关闭原因")
    @Size(min = 5, max = 500, message = "关闭原因需为 5-500 个字符")
    private String reason;
}
