package com.example.newmusic.module.admin.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("后台登录结果")
public class AdminLoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private AdminUserVO admin;

    @ApiModelProperty("accessToken 秒数")
    private long expiresIn;

    @ApiModelProperty("refreshToken 秒数")
    private long refreshExpiresIn;

    @ApiModelProperty("1=必须先修改密码（管理员重置或超过 90 天）")
    private boolean forceChangePassword;

    @ApiModelProperty("密码是否超过 90 天")
    private boolean passwordExpired;

    @ApiModelProperty("是否开启了 TOTP")
    private boolean totpEnabled;
}
