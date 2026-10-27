package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收藏关系（模块 10）。folderId 为空表示默认收藏夹。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_collect")
public class Collect extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private String targetType;

    private Long targetId;

    private Long folderId;
}
