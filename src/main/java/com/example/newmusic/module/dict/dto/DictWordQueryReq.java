package com.example.newmusic.module.dict.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("主词典查询请求")
public class DictWordQueryReq extends PageQuery {

    @ApiModelProperty("来源 MANUAL / AUTO_ARTIST / AUTO_SONG")
    private String source;

    @ApiModelProperty("词典类型 MAIN / NET_SLANG / REGION")
    private String dictType;

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled;
}
