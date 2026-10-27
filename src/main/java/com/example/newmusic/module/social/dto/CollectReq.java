package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("收藏/取消收藏")
public class CollectReq {

    @ApiModelProperty("SONG/PLAYLIST/ALBUM/ROOM")
    @NotBlank(message = "请选择收藏目标")
    private String targetType;

    @NotNull(message = "收藏目标不能为空")
    private Long targetId;

    @ApiModelProperty("目标收藏夹；不传表示默认收藏夹")
    private Long folderId;

    @ApiModelProperty("true 收藏，false 取消；缺省按 true")
    private Boolean collect;
}
