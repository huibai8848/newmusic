package com.example.newmusic.module.dict.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("同义词查询请求")
public class SynonymQueryReq extends PageQuery {

    @ApiModelProperty("ARTIST / GENRE / LANGUAGE / ERA / SCENE / EMOTION / OTHER")
    private String category;

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled;
}
