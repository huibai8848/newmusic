package com.example.newmusic.module.user.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("登录结果")
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("访问令牌，放 Authorization: Bearer")
    private String accessToken;

    @ApiModelProperty("刷新令牌")
    private String refreshToken;

    @ApiModelProperty("accessToken 有效期（秒）")
    private Long expiresIn;

    @ApiModelProperty("用户信息（含 roles / perms，供前端按钮级控制）")
    private UserVO user;
}
