package com.example.newmusic.module.contentsafety.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("敏感词查询")
public class WordQueryReq extends PageQuery {

    private Integer level;
    private String category;
    private String source;
    private Integer status;
    private String keyword;
}
