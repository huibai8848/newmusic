package com.example.newmusic.module.socialupgrade.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("群成员")
public class GroupMemberVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String nickname;
    private String avatar;
    /** 1 群主 2 管理员 3 成员 */
    private Integer role;
    private Boolean owner;
}
