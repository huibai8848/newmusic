package com.example.newmusic.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色权限关联表 t_role_permission（物理删除）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role_permission")
public class RolePermission extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long roleId;
    private Long permId;
}
