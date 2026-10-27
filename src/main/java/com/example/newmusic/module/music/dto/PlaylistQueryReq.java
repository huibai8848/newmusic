package com.example.newmusic.module.music.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("歌单查询参数")
public class PlaylistQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("创建者ID")
    private Long userId;

    @ApiModelProperty("1用户 2编辑 3算法 4场景")
    private Integer playlistType;

    @ApiModelProperty("0私密 1公开")
    private Integer isPublic;

    @ApiModelProperty("是否首页推荐")
    private Integer isRecommend;

    @ApiModelProperty("仅看我的歌单（需登录，忽略 userId 参数）")
    private Boolean mine;

    @ApiModelProperty("标签，逗号分隔值精确匹配其中之一")
    private String tag;
}
