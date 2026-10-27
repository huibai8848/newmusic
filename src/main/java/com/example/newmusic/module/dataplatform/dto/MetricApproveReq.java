package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/** 指标口径变更审批。 */
@Data
@ApiModel("指标口径审批")
public class MetricApproveReq {

    @ApiModelProperty(value = "true 通过 / false 驳回", required = true)
    private Boolean approve;

    @ApiModelProperty("审批意见")
    private String comment;

    @ApiModelProperty("生效日期 yyyy-MM-dd，默认今天")
    private String effectiveDate;
}
