package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 私信消息（模块 10 P1）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_private_message")
public class PrivateMessage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_SONG = "SONG";
    public static final String TYPE_PLAYLIST = "PLAYLIST";
    public static final String TYPE_ROOM = "ROOM";

    private Long conversationId;

    private Long senderId;

    private Long receiverId;

    private String msgType;

    private String content;

    /** 模块 16：引用的消息 ID（软引用，被引用消息删除/撤回后展示提示） */
    private Long quoteMsgId;

    /** 模块 16：是否为转发消息（转发后不展示原发送者） */
    private Integer forwarded;

    /** 模块 16：群聊中被 @ 的成员 ID，逗号分隔 */
    private String mentionedUserIds;

    private Integer isRead;

    private LocalDateTime readTime;

    private Integer isRevoked;

    private LocalDateTime revokeTime;

    private Integer deletedBySender;

    private Integer deletedByReceiver;
}
