package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.social.dto.NotificationSettingReq;
import com.example.newmusic.module.social.entity.NotificationSetting;
import com.example.newmusic.module.social.mapper.NotificationSettingMapper;
import com.example.newmusic.module.ws.entity.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知分类开关（模块 10）。设置不存在时按全开处理，首次 PUT 才落库。
 */
@Service
public class NotificationSettingService {

    private final NotificationSettingMapper mapper;

    public NotificationSettingService(NotificationSettingMapper mapper) {
        this.mapper = mapper;
    }

    public NotificationSetting get(Long userId) {
        NotificationSetting setting = mapper.selectOne(
                new QueryWrapper<NotificationSetting>().eq("user_id", userId));
        return setting == null ? NotificationSetting.defaults(userId) : setting;
    }

    @Transactional
    public NotificationSetting update(Long userId, NotificationSettingReq req) {
        NotificationSetting setting = mapper.selectOne(
                new QueryWrapper<NotificationSetting>().eq("user_id", userId));
        if (setting == null) {
            setting = NotificationSetting.defaults(userId);
        }
        if (req.getLikeEnabled() != null) {
            setting.setLikeEnabled(req.getLikeEnabled());
        }
        if (req.getCommentEnabled() != null) {
            setting.setCommentEnabled(req.getCommentEnabled());
        }
        if (req.getFollowEnabled() != null) {
            setting.setFollowEnabled(req.getFollowEnabled());
        }
        if (req.getMessageEnabled() != null) {
            setting.setMessageEnabled(req.getMessageEnabled());
        }
        if (req.getSystemEnabled() != null) {
            setting.setSystemEnabled(req.getSystemEnabled());
        }
        if (req.getNewSongEnabled() != null) {
            setting.setNewSongEnabled(req.getNewSongEnabled());
        }
        if (req.getReadReceiptEnabled() != null) {
            setting.setReadReceiptEnabled(req.getReadReceiptEnabled());
        }
        if (setting.getId() == null) {
            mapper.insert(setting);
        } else {
            mapper.updateById(setting);
        }
        return setting;
    }

    /** 是否允许向该用户推送某一类通知。 */
    public boolean enabled(Long userId, int notifyType) {
        if (userId == null) {
            return false;
        }
        NotificationSetting s = get(userId);
        switch (notifyType) {
            case Notification.TYPE_LIKE:
                return on(s.getLikeEnabled());
            case Notification.TYPE_COMMENT:
            case Notification.TYPE_MENTION:
                return on(s.getCommentEnabled());
            case Notification.TYPE_FOLLOW:
                return on(s.getFollowEnabled());
            case Notification.TYPE_MESSAGE:
                return on(s.getMessageEnabled());
            case Notification.TYPE_NEW_SONG:
                return on(s.getNewSongEnabled());
            default:
                return on(s.getSystemEnabled());
        }
    }

    private static boolean on(Integer value) {
        return value == null || value == 1;
    }
}
