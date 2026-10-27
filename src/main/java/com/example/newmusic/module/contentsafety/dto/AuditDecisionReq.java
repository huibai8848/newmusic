package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("人工审核决策")
public class AuditDecisionReq {

    @ApiModelProperty("APPROVE / REJECT")
    @NotBlank(message = "请选择审核结论")
    private String decision;

    @ApiModelProperty("拒绝原因；拒绝时必填")
    @Size(max = 500)
    private String rejectReason;

    @ApiModelProperty("内容处置：NONE/PASS/DELETE/HIDE/SELF_ONLY/MASK")
    private String contentAction;

    @ApiModelProperty("用户处置：NONE/WARN/RESTRICT_COMMENT/MUTE/BAN/RESTRICT_UPLOAD")
    private String userAction;

    @ApiModelProperty("用户处置时长（天）；为空按默认")
    private Integer userActionDays;

    @ApiModelProperty("是否通知用户，默认 true")
    private Boolean notify;
}
