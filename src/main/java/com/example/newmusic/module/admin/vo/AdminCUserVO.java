package com.example.newmusic.module.admin.vo;

import com.example.newmusic.module.user.entity.User;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台看到的 C 端用户。手机号默认脱敏；查看完整号码走独立敏感接口。
 */
@Data
@ApiModel("后台-C端用户")
public class AdminCUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String phoneMasked;
    private String email;
    private Integer gender;
    private Integer userType;
    private Integer status;
    private String banReason;
    private LocalDateTime banUntil;
    private String registerIp;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private Integer vipLevel;
    private Integer forceChangePassword;
    private LocalDateTime createTime;

    public static AdminCUserVO from(User user) {
        if (user == null) {
            return null;
        }
        AdminCUserVO vo = new AdminCUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhoneMasked(maskPhone(user.getPhone()));
        vo.setEmail(user.getEmail());
        vo.setGender(user.getGender());
        vo.setUserType(user.getUserType());
        vo.setStatus(user.getStatus());
        vo.setBanReason(user.getBanReason());
        vo.setBanUntil(user.getBanUntil());
        vo.setRegisterIp(user.getRegisterIp());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setVipLevel(user.getVipLevel());
        vo.setForceChangePassword(user.getForceChangePassword());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? null : "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
