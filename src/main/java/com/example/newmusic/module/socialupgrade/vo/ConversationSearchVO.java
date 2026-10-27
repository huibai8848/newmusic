package com.example.newmusic.module.socialupgrade.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@ApiModel("会话搜索")
public class ConversationSearchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ConversationItemVO> conversations;

    private List<MessageHitVO> messages;

    @Data
    @ApiModel("消息搜索命中")
    public static class MessageHitVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long messageId;
        private Long conversationId;
        private String conversationName;
        private Long senderId;
        private String senderNickname;
        private String content;
        private LocalDateTime createTime;
    }
}
