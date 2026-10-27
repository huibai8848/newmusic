package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("私信会话")
public class ConversationVO {

    private Long id;

    private Long otherUserId;

    private String nickname;

    private String avatar;

    private String lastMessagePreview;

    private LocalDateTime lastMessageTime;

    private Integer unread;
}
