package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("生成分享短链")
public class ShareReq {

    @ApiModelProperty("SONG/PLAYLIST/ALBUM")
    @NotBlank(message = "请选择分享目标")
    private String targetType;

    @NotNull(message = "分享目标不能为空")
    private Long targetId;

    @ApiModelProperty("COPY_LINK/IN_APP/SYSTEM/THIRD_PARTY")
    private String channel;
}
