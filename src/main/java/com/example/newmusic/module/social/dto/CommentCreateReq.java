package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("发表评论")
public class CommentCreateReq {

    @ApiModelProperty("SONG/PLAYLIST/ALBUM/ROOM")
    @NotBlank(message = "请选择评论目标")
    private String targetType;

    @NotNull(message = "评论目标不能为空")
    private Long targetId;

    @NotBlank(message = "评论内容不能为空")
    private String content;

    @ApiModelProperty("回复的根评论ID；顶级评论不传")
    private Long rootId;

    @ApiModelProperty("回复的父评论ID；回复顶级评论时等于 rootId")
    private Long parentId;
}
