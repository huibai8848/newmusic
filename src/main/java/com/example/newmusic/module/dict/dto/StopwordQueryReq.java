package com.example.newmusic.module.dict.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("停用词查询请求")
public class StopwordQueryReq extends PageQuery {

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled;
}
