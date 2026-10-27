package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 会话成员（模块 16 FR-25-010，单聊与群聊共用）。 */
@Data
@TableName("t_conversation_member")
public class ConversationMember implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int ROLE_OWNER = 1;
    public static final int ROLE_ADMIN = 2;
    public static final int ROLE_MEMBER = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long userId;
    private Integer role;
    private Integer pinned;
    private Integer archived;
    private Integer muted;
    private Long lastReadMsgId;
    private LocalDateTime joinedTime;
    private LocalDateTime quitTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
