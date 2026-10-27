package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
@ApiModel("处理审核任务")
public class AuditHandleReq {

    @ApiModelProperty("APPROVE / REJECT")
    @NotBlank(message = "请选择处理结论")
    @Pattern(regexp = "APPROVE|REJECT", message = "结论只能为 APPROVE 或 REJECT")
    private String result;

    @ApiModelProperty("拒绝时必填，不少于 5 个字符")
    @Size(max = 500, message = "理由不能超过 500 字")
    private String reason;

    @ApiModelProperty("乐观锁版本号；未领取直接处理时可不传")
    private Integer version;
}
