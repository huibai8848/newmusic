package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 后台管理员账号（与 C 端 {@code t_user} 完全独立）。
 *
 * <p>密码与 TOTP 密钥永不出现在 JSON 中；管理端接口一律返回 {@code AdminUserVO}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_user")
@ApiModel("后台管理员")
public class AdminUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_DISABLED = 2;

    private String username;

    @JsonIgnore
    private String password;

    private String realName;
    private String email;
    private String phone;

    @ApiModelProperty("1正常 2禁用")
    private Integer status;

    @JsonIgnore
    private String totpSecret;
    private Integer totpEnabled;

    @ApiModelProperty("1=下次登录必须改密")
    private Integer forceChangePassword;

    private LocalDateTime passwordUpdateTime;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;

    @ApiModelProperty("连续失败次数；库内权威")
    private Integer failCount;

    @ApiModelProperty("锁定到期时间")
    private LocalDateTime lockUntil;

    @ApiModelProperty("IP 白名单，逗号分隔；空=不限制")
    private String allowIpList;

    private String remark;
}
