package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("群聊消息")
public class GroupMessageReq {

    @ApiModelProperty("TEXT/IMAGE/SONG/PLAYLIST/ROOM，默认 TEXT")
    private String msgType;

    @NotBlank(message = "消息内容不能为空")
    private String content;

    @ApiModelProperty("引用的消息 ID，必须属于当前会话")
    private Long quoteMsgId;
}
