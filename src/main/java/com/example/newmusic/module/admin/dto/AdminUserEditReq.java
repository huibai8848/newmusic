package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.Size;

@Data
@ApiModel("编辑用户资料")
public class AdminUserEditReq {

    @Size(max = 50, message = "昵称不能超过 50 字")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;

    @ApiModelProperty("1普通用户 2运营；不允许通过后台直接改为 3/4（后台身份独立于 C 端）")
    private Integer userType;
}
