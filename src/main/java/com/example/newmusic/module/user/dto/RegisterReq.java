package com.example.newmusic.module.user.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("注册请求")
public class RegisterReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "用户名（4~20 位字母/数字/下划线）", required = true, example = "demo")
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "用户名需为 4~20 位字母、数字或下划线")
    private String username;

    @ApiModelProperty(value = "密码（8~32 位，需含字母和数字）", required = true, example = "Demo@1234")
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String password;

    @ApiModelProperty(value = "昵称", required = true, example = "演示用户")
    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称不能超过 20 个字")
    private String nickname;
}
