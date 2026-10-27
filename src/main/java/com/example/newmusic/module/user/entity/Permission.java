package com.example.newmusic.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 权限表 t_permission。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission")
public class Permission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String permCode;
    private String permName;
    private Integer permType;
    private Long parentId;
    private String path;
    private String icon;
    private Integer sortOrder;
}
