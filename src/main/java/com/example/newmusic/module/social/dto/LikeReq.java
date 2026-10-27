package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("点赞/取消点赞")
public class LikeReq {

    @ApiModelProperty("SONG/PLAYLIST/ALBUM/COMMENT/ROOM_MESSAGE")
    @NotBlank(message = "请选择点赞目标")
    private String targetType;

    @NotNull(message = "点赞目标不能为空")
    private Long targetId;

    @ApiModelProperty("true 点赞，false 取消；缺省按 true")
    private Boolean like;
}
