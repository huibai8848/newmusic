package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_role_permission")
public class AdminRolePermission extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long roleId;
    private Long permId;
}
