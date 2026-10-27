package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 私信会话（模块 10 P1）。约定 userAId < userBId，唯一键保证两人只有一个会话。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_conversation")
public class Conversation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_SINGLE = 1;
    public static final int TYPE_GROUP = 2;

    private Long userAId;

    /**
     * 单聊时恒为较大的 userId；群聊时写入会话 ID。
     *
     * <p>群聊没有“对方 userId”，但既有唯一键 {@code uk_conversation_pair} 与模块 10 的
     * 查询都依赖这两列。把 user_b_id 填成会话 ID 既保证同群主可建多个群，又能让
     * {@code type = 1} 的过滤把群聊完全隔离在旧接口之外。
     */
    private Long userBId;

    /** 1 单聊 2 群聊（模块 16 FR-25-010） */
    private Integer type;

    private String groupName;

    private String groupAvatar;

    private Long ownerId;

    private Integer memberCount;

    private Long lastMessageId;

    private LocalDateTime lastMessageTime;

    private String lastMessagePreview;

    /** A 的未读数（B 发来的） */
    private Integer unreadA;

    /** B 的未读数（A 发来的） */
    private Integer unreadB;

    public boolean isGroup() {
        return Integer.valueOf(TYPE_GROUP).equals(type);
    }

    public Long otherUserId(Long me) {
        return me.equals(userAId) ? userBId : userAId;
    }

    public Integer unreadOf(Long me) {
        return me.equals(userAId) ? unreadA : unreadB;
    }
}
