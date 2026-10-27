package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("发送私信")
public class MessageSendReq {

    @NotNull(message = "请选择接收人")
    private Long targetUserId;

    @ApiModelProperty("TEXT/IMAGE/SONG/PLAYLIST/ROOM")
    private String msgType;

    @NotBlank(message = "消息内容不能为空")
    private String content;
}
