package com.example.newmusic.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户表 t_user（《04-数据模型与数据库设计》2.1）。
 *
 * <p>敏感字段 password / phone 不出现在 JSON 中；对外统一用 UserVO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
@ApiModel("用户")
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("用户名")
    private String username;

    @JsonIgnore
    private String phone;

    @JsonIgnore
    private String phoneHash;

    @ApiModelProperty("邮箱")
    private String email;

    @JsonIgnore
    private String password;

    @ApiModelProperty("昵称")
    private String nickname;

    @ApiModelProperty("头像URL")
    private String avatar;

    @ApiModelProperty("性别 0未知 1男 2女")
    private Integer gender;

    @ApiModelProperty("生日")
    private LocalDate birthday;

    @ApiModelProperty("个性签名")
    private String signature;

    @ApiModelProperty("用户类型 1普通 2运营 3管理员 4超管")
    private Integer userType;

    @ApiModelProperty("状态 0待验证 1正常 2封禁 3注销中 4已注销")
    private Integer status;

    /** 模块 09：管理员重置密码后置 1，C 端登录响应提示用户先改密。 */
    @ApiModelProperty("1=下次登录必须修改密码")
    private Integer forceChangePassword;

    private String banReason;
    private LocalDateTime banUntil;
    private String registerIp;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private Integer vipLevel;
    private LocalDateTime vipExpireTime;
}
