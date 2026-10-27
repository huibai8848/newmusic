package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("用户处置")
public class RestrictReq {

    @ApiModelProperty("1限制发言 2禁言 3封禁 5限制上传")
    @Min(1)
    @Max(5)
    private Integer type;

    @ApiModelProperty("处置等级 L1~L5")
    @Min(1)
    @Max(5)
    private Integer level;

    @NotBlank(message = "请填写处置原因")
    @Size(min = 2, max = 500)
    private String reason;

    private String reasonCategory;

    @ApiModelProperty("时长（天）；永久封禁可不填")
    @Min(1)
    @Max(3650)
    private Integer days;

    private String relatedContentType;
    private Long relatedContentId;
    private Long relatedReportId;
    private String remark;
}
