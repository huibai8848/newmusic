package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("修改后台密码")
public class AdminPasswordChangeReq {

    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
    private String newPassword;
}
