package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 个人资料修改日志（BR-25-021）。 */
@Data
@TableName("t_user_profile_log")
public class UserProfileLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String field;
    private String oldValue;
    private String newValue;
    private String ip;
    private LocalDateTime createTime;
}
