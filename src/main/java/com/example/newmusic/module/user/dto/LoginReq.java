package com.example.newmusic.module.user.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
@ApiModel("登录请求")
public class LoginReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "用户名或手机号", required = true, example = "admin")
    @NotBlank(message = "账号不能为空")
    private String account;

    @ApiModelProperty(value = "密码", required = true, example = "Admin@123")
    @NotBlank(message = "密码不能为空")
    private String password;
}
