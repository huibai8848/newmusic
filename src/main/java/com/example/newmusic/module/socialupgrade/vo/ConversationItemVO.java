package com.example.newmusic.module.socialupgrade.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@ApiModel("会话（单聊/群聊统一）")
public class ConversationItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @ApiModelProperty("1 单聊 2 群聊")
    private Integer type;

    @ApiModelProperty("单聊为对方昵称，群聊为群名称")
    private String name;

    private String avatar;

    @ApiModelProperty("单聊的对方 userId；群聊为空")
    private Long otherUserId;

    private String lastMessagePreview;

    private LocalDateTime lastMessageTime;

    private Integer unread;

    private Boolean pinned;

    private Boolean archived;

    private Boolean muted;

    private Integer memberCount;

    @ApiModelProperty("群主 userId；单聊为空")
    private Long ownerId;
}
