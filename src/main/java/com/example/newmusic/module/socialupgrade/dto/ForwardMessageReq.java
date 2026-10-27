package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("转发消息")
public class ForwardMessageReq {

    @NotNull(message = "请选择目标会话")
    private Long conversationId;
}
