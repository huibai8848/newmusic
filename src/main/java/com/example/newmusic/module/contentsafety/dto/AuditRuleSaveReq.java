package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("新增/编辑审核规则")
public class AuditRuleSaveReq {

    @NotBlank(message = "规则名不能为空")
    @Size(max = 64)
    private String ruleName;

    @Size(max = 16)
    private String ruleType;

    @NotBlank(message = "规则内容不能为空")
    @Size(max = 512)
    private String pattern;

    @Size(max = 32)
    private String scene;

    @Size(max = 16)
    private String action;

    private Integer priority;
    private Integer status;
    private String remark;
}
