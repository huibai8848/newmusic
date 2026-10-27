package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 点赞关系（模块 10）。关联表：物理删除 + 唯一索引保证幂等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_like")
public class LikeRecord extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private String targetType;

    private Long targetId;
}
