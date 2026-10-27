package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("私信消息")
public class PrivateMessageVO {

    private Long id;

    private Long conversationId;

    private Long senderId;

    private Long receiverId;

    private String msgType;

    private String content;

    /** 群聊展示发送者信息；单聊前端可忽略 */
    private String senderNickname;

    private String senderAvatar;

    private Boolean forwarded;

    private Quote quote;

    private Boolean isRead;

    private Boolean isRevoked;

    private Boolean mine;

    private LocalDateTime createTime;

    @Data
    @ApiModel("引用消息摘要")
    public static class Quote {
        private Long messageId;
        private Long senderId;
        private String senderNickname;
        private String content;
        private Boolean revoked;
    }
}
