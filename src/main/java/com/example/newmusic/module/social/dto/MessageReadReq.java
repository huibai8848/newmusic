package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("会话标记已读")
public class MessageReadReq {

    @NotNull(message = "会话ID不能为空")
    private Long conversationId;
}
