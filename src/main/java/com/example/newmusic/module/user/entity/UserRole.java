package com.example.newmusic.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户角色关联表 t_user_role（物理删除，系分决策 D-03）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_role")
public class UserRole extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long roleId;
}
