package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("新增/编辑管理员")
public class AdminAccountSaveReq {

    @NotBlank(message = "请输入登录名")
    @Size(min = 3, max = 64, message = "登录名长度需为 3-64 位")
    private String username;

    @ApiModelProperty("新增时必填；编辑时留空表示不改密码")
    private String password;

    @Size(max = 64, message = "姓名不能超过 64 字")
    private String realName;

    @Email(message = "邮箱格式不正确")
    private String email;

    private String phone;

    @ApiModelProperty("SUPER_ADMIN / ADMIN / OPERATOR")
    @NotBlank(message = "请选择角色")
    private String roleCode;

    @ApiModelProperty("启用 TOTP")
    private Boolean totpEnabled;

    @ApiModelProperty("IP 白名单，逗号分隔")
    private String allowIpList;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;

    private List<Long> roleIds;
}
