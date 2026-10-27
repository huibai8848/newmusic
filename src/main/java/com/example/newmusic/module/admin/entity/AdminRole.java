package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_role")
public class AdminRole extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";

    private String roleCode;
    private String roleName;
    private String description;
    private Integer status;
}
