package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 分享短链（模块 10 P1）。Redis 存映射，本表用于统计与审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_share")
public class ShareLink extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private String code;

    private String targetType;

    private Long targetId;

    private Long sharerId;

    private String channel;

    private Integer clickCount;

    private LocalDateTime expireTime;
}
