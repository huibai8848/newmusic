package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("创建单聊会话")
public class CreateConversationReq {

    @ApiModelProperty("对方 userId")
    @NotNull(message = "请选择对方用户")
    private Long targetUserId;
}
