package com.example.newmusic.module.contentsafety.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("申诉查询")
public class AppealQueryReq extends PageQuery {

    private Integer status;
    private String targetType;
    private Boolean overdue;
}
