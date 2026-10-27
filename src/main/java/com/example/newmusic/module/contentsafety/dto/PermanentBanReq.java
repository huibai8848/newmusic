package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("永久封禁")
public class PermanentBanReq {

    @NotBlank(message = "请填写详细原因")
    @Size(min = 10, max = 500, message = "永久封禁原因至少 10 字")
    private String reason;

    @ApiModelProperty("必须为 BAN")
    @NotBlank(message = "请输入 BAN 二次确认")
    private String confirm;

    private String reasonCategory;
    private Long relatedReportId;
}
