package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收藏夹（模块 10）。物理删除：删除后同名可重新创建，不会被唯一键挡住。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_collect_folder")
public class CollectFolder extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private String name;

    private Integer isDefault;

    private Integer itemCount;

    private Integer sortOrder;
}
