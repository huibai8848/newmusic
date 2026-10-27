package com.example.newmusic.module.music.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("专辑查询参数")
public class AlbumQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌手ID")
    private Long artistId;

    @ApiModelProperty("1上架 0下架")
    private Integer status;
}
