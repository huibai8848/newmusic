package com.example.newmusic.module.user.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
@ApiModel("刷新令牌请求")
public class RefreshReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "登录时返回的 refreshToken", required = true)
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
