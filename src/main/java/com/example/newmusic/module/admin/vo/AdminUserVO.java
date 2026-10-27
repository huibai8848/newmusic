package com.example.newmusic.module.admin.vo;

import com.example.newmusic.module.admin.entity.AdminUser;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;

@Data
@ApiModel("后台管理员信息")
public class AdminUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String realName;
    private String email;
    private String phoneMasked;
    private Integer status;
    private Integer totpEnabled;
    private Integer forceChangePassword;
    private LocalDateTime passwordUpdateTime;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private String allowIpList;
    private String remark;
    private LocalDateTime createTime;
    private Set<String> roles = Collections.emptySet();
    private Set<String> perms = Collections.emptySet();

    public static AdminUserVO from(AdminUser user) {
        if (user == null) {
            return null;
        }
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setPhoneMasked(maskPhone(user.getPhone()));
        vo.setStatus(user.getStatus());
        vo.setTotpEnabled(user.getTotpEnabled());
        vo.setForceChangePassword(user.getForceChangePassword());
        vo.setPasswordUpdateTime(user.getPasswordUpdateTime());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setAllowIpList(user.getAllowIpList());
        vo.setRemark(user.getRemark());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
