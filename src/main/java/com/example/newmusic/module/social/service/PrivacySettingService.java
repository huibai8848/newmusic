package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.social.dto.PrivacySettingReq;
import com.example.newmusic.module.social.entity.PrivacySetting;
import com.example.newmusic.module.social.mapper.PrivacySettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐私设置（模块 10 FR-19-014 / D-07）。缺省行不存在时按默认值处理。
 */
@Service
public class PrivacySettingService {

    private final PrivacySettingMapper mapper;

    public PrivacySettingService(PrivacySettingMapper mapper) {
        this.mapper = mapper;
    }

    public PrivacySetting get(Long userId) {
        PrivacySetting setting = mapper.selectOne(
                new QueryWrapper<PrivacySetting>().eq("user_id", userId));
        return setting == null ? PrivacySetting.defaults(userId) : setting;
    }

    @Transactional
    public PrivacySetting update(Long userId, PrivacySettingReq req) {
        PrivacySetting setting = mapper.selectOne(
                new QueryWrapper<PrivacySetting>().eq("user_id", userId));
        if (setting == null) {
            setting = PrivacySetting.defaults(userId);
        }
        if (req.getMessagePolicy() != null) {
            setting.setMessagePolicy(req.getMessagePolicy());
        }
        if (req.getCommentPolicy() != null) {
            setting.setCommentPolicy(req.getCommentPolicy());
        }
        if (req.getMentionPolicy() != null) {
            setting.setMentionPolicy(req.getMentionPolicy());
        }
        if (req.getShowFollowing() != null) {
            setting.setShowFollowing(req.getShowFollowing());
        }
        if (req.getShowFollowers() != null) {
            setting.setShowFollowers(req.getShowFollowers());
        }
        if (req.getPlayHistoryVisibility() != null) {
            setting.setPlayHistoryVisibility(req.getPlayHistoryVisibility());
        }
        if (req.getReadReceipt() != null) {
            setting.setReadReceipt(req.getReadReceipt());
        }
        if (req.getNewSongNotify() != null) {
            setting.setNewSongNotify(req.getNewSongNotify());
        }
        if (req.getSystemNotify() != null) {
            setting.setSystemNotify(req.getSystemNotify());
        }
        if (setting.getId() == null) {
            mapper.insert(setting);
        } else {
            mapper.updateById(setting);
        }
        return setting;
    }

    /** 接收者是否拒绝接收来自 {@code senderId} 的私信。 */
    public boolean messageForbidden(Long receiverId, Long senderId, boolean mutual) {
        PrivacySetting setting = get(receiverId);
        Integer policy = setting.getMessagePolicy() == null ? 0 : setting.getMessagePolicy();
        if (policy == 2) {
            return true;
        }
        return policy == 1 && !mutual;
    }
}
