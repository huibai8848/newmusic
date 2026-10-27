package com.example.newmusic.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色表 t_role。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role")
public class Role extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String roleCode;
    private String roleName;
    private String description;
    private Integer status;
}
