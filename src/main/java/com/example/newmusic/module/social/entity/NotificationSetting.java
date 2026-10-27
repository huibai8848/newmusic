package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知设置（模块 10）。缺省行不存在时按全开处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notification_setting")
public class NotificationSetting extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Integer likeEnabled;

    private Integer commentEnabled;

    private Integer followEnabled;

    private Integer messageEnabled;

    private Integer systemEnabled;

    private Integer newSongEnabled;

    private Integer readReceiptEnabled;

    public static NotificationSetting defaults(Long userId) {
        NotificationSetting s = new NotificationSetting();
        s.setUserId(userId);
        s.setLikeEnabled(1);
        s.setCommentEnabled(1);
        s.setFollowEnabled(1);
        s.setMessageEnabled(1);
        s.setSystemEnabled(1);
        s.setNewSongEnabled(1);
        s.setReadReceiptEnabled(1);
        return s;
    }
}
