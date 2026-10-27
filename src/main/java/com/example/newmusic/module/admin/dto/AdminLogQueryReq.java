package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("日志查询")
public class AdminLogQueryReq extends PageQuery {

    private Long operatorId;
    private String opType;
    private String targetType;
    private Integer result;
    private LocalDate startDate;
    private LocalDate endDate;
}
