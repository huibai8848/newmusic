package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_permission")
public class AdminPermission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String permCode;
    private String permName;
    private String permGroup;
    private String description;

    /**
     * 1 = 敏感权限；使用必须写详细日志并通知超管（FR-18-019）。
     *
     * <p>{@code SENSITIVE} 是 MySQL 8 的保留字，列名必须加反引号，
     * 否则超级管理员登录时 {@code selectList} 生成的全列查询会直接语法报错。
     */
    @TableField("`sensitive`")
    private Integer sensitive;
    private Integer sortOrder;
    private Integer status;
}
