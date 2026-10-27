package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 登录日志（C 端 + 后台共用）。 */
@Data
@TableName("t_login_log")
public class LoginLog implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_C = 1;
    public static final int TYPE_ADMIN = 2;

    public static final int RESULT_FAIL = 0;
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_LOCKED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String username;
    private Integer loginType;
    private String ip;
    private String location;
    private String userAgent;
    private String device;
    private Integer result;
    private String failReason;
    private Integer suspicious;
    private LocalDateTime createTime;
}
