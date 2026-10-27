package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("举报查询")
public class ReportQueryReq extends PageQuery {

    private Integer status;
    private Integer priority;
    private String targetType;
}
