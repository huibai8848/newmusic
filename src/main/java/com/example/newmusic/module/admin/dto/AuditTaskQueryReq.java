package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("审核任务查询")
public class AuditTaskQueryReq extends PageQuery {

    private Integer status;
    private Integer priority;
    private String taskType;
    private String targetType;
    private Boolean onlyMine;
    private Boolean escalated;
}
