package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel("用户关注状态")
public class FollowStatusVO {

    private Long userId;

    private Boolean followed;

    private Boolean mutual;

    private Long followingCount;

    private Long followerCount;
}
