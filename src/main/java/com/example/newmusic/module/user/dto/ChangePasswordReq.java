package com.example.newmusic.module.user.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("修改密码")
public class ChangePasswordReq {

    @NotBlank(message = "原密码不能为空")
    @ApiModelProperty(value = "原密码；被管理员重置过时填管理员提供的临时密码", required = true)
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需为 8~64 位")
    @ApiModelProperty(value = "新密码：8~64 位，至少包含字母与数字", required = true)
    private String newPassword;
}
