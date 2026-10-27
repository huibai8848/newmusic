package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("用户摘要")
public class UserBriefVO {

    private Long id;

    private String nickname;

    private String avatar;

    private String signature;

    @ApiModelProperty("当前登录用户是否关注了 TA")
    private Boolean followed;

    @ApiModelProperty("是否互关")
    private Boolean mutual;
}
