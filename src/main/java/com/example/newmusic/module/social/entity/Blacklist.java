package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户黑名单（模块 10）。userId 拉黑了 blockedUserId。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_blacklist")
public class Blacklist extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long blockedUserId;
}
