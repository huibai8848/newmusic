package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户关注（单向，模块 10）。关联表：物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_follow")
public class Follow extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long followerId;

    private Long followingId;
}
