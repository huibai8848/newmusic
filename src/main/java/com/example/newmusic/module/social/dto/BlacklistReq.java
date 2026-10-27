package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("拉黑用户")
public class BlacklistReq {

    @NotNull(message = "请选择用户")
    private Long targetUserId;
}
