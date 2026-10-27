package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("会话设置开关")
public class ConversationSettingReq {

    @NotNull(message = "缺少开关值")
    private Boolean enabled;
}
