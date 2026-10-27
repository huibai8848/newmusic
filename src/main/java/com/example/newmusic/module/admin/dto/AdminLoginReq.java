package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("后台登录请求")
public class AdminLoginReq {

    @NotBlank(message = "请输入账号")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;

    @NotBlank(message = "请先获取验证码")
    private String captchaId;

    @NotBlank(message = "请输入验证码")
    private String captchaCode;

    @ApiModelProperty("开启 TOTP 的账号必填")
    private String totpCode;
}
