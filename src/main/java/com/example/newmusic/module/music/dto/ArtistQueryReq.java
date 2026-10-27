package com.example.newmusic.module.music.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("歌手查询参数")
public class ArtistQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("国家/地区")
    private String country;

    @ApiModelProperty("首字母 A-Z")
    private String firstLetter;

    @ApiModelProperty("1正常 0禁用")
    private Integer status;
}
