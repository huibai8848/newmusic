package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("用户目标请求（房管/黑名单/转让）")
public class UserTargetReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "目标用户ID", required = true)
    @NotNull(message = "userId 不能为空")
    private Long userId;
}
