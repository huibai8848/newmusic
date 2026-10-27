package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户隐私设置（模块 10 P1）。
 *
 * <p>messagePolicy：0 所有人 / 1 互关 / 2 关闭；
 * commentPolicy：0 所有人 / 1 关注我的人 / 2 关闭；
 * playHistoryVisibility：0 所有人 / 1 互关 / 2 仅自己。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_privacy_setting")
public class PrivacySetting extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Integer messagePolicy;

    private Integer commentPolicy;

    private Integer mentionPolicy;

    private Integer showFollowing;

    private Integer showFollowers;

    private Integer playHistoryVisibility;

    private Integer readReceipt;

    private Integer newSongNotify;

    private Integer systemNotify;

    public static PrivacySetting defaults(Long userId) {
        PrivacySetting s = new PrivacySetting();
        s.setUserId(userId);
        s.setMessagePolicy(0);
        s.setCommentPolicy(0);
        s.setMentionPolicy(0);
        s.setShowFollowing(1);
        s.setShowFollowers(1);
        s.setPlayHistoryVisibility(2);
        s.setReadReceipt(1);
        s.setNewSongNotify(1);
        s.setSystemNotify(1);
        return s;
    }
}
