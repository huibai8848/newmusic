package com.example.newmusic.module.dict.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("词典变更历史查询请求")
public class DictChangeLogQueryReq extends PageQuery {

    @ApiModelProperty("MAIN / STOPWORD / SYNONYM / PINYIN")
    private String dictType;

    @ApiModelProperty("CREATE / UPDATE / DELETE / IMPORT / SYNC / RELOAD / REINDEX")
    private String changeType;

    private Long operatorId;
}
