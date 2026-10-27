package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("关注/取关用户")
public class FollowReq {

    @NotNull(message = "请选择用户")
    private Long targetUserId;
}
