package com.example.newmusic.module.user.vo;

import com.example.newmusic.module.user.entity.User;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;

/**
 * 用户对外视图：不含 password / phone / 审计字段。
 */
@Data
@ApiModel("用户信息")
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("用户ID（字符串，防前端精度丢失）")
    private Long id;

    @ApiModelProperty("用户名")
    private String username;

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

    @ApiModelProperty("状态 1正常 2封禁")
    private Integer status;

    @ApiModelProperty("1=管理员重置过密码，下次登录后应先改密")
    private Integer forceChangePassword;

    @ApiModelProperty("会员等级（预留）")
    private Integer vipLevel;

    @ApiModelProperty("注册时间")
    private LocalDateTime createTime;

    @ApiModelProperty("角色码集合")
    private Set<String> roles = Collections.emptySet();

    @ApiModelProperty("权限码集合")
    private Set<String> perms = Collections.emptySet();

    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setBirthday(user.getBirthday());
        vo.setSignature(user.getSignature());
        vo.setUserType(user.getUserType());
        vo.setStatus(user.getStatus());
        vo.setForceChangePassword(user.getForceChangePassword());
        vo.setVipLevel(user.getVipLevel());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
