package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 指标定义新增/修改；口径变更走审批（BR-26-028）。 */
@Data
@ApiModel("指标定义保存")
public class MetricSaveReq {

    @ApiModelProperty(value = "指标 ID", required = true, example = "metric_dau_play")
    @NotBlank(message = "metricId 不能为空")
    private String metricId;

    @ApiModelProperty(value = "指标名", required = true)
    @NotBlank(message = "metricName 不能为空")
    private String metricName;

    @ApiModelProperty("分类")
    private String category;

    @ApiModelProperty(value = "口径定义", required = true)
    @NotBlank(message = "definition 不能为空")
    private String definition;

    @ApiModelProperty("计算逻辑")
    private String calculation;

    @ApiModelProperty("数据源")
    private String dataSource;

    @ApiModelProperty("可用维度 JSON")
    private String dimensions;

    @ApiModelProperty("更新频率")
    private String updateFreq;

    private String owner;

    @ApiModelProperty("口径版本")
    private String version;

    @ApiModelProperty("0 草稿 1 生效 2 废弃")
    private Integer status;

    @ApiModelProperty("口径变更原因（修改已生效指标时必填）")
    private String reason;
}
