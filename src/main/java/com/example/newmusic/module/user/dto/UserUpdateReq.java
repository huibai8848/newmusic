package com.example.newmusic.module.user.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@ApiModel("修改个人资料请求")
public class UserUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("昵称")
    @Size(max = 20, message = "昵称不能超过 20 个字")
    private String nickname;

    @ApiModelProperty("头像URL")
    @Size(max = 500, message = "头像地址过长")
    private String avatar;

    @ApiModelProperty("性别 0未知 1男 2女")
    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;

    @ApiModelProperty("生日，格式 yyyy-MM-dd")
    private LocalDate birthday;

    @ApiModelProperty("个性签名")
    @Size(max = 200, message = "个性签名不能超过 200 字")
    private String signature;
}
