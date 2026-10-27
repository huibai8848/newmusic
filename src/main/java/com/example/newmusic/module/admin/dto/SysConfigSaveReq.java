package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
@ApiModel("新增系统配置")
public class SysConfigSaveReq {

    @NotBlank(message = "配置键不能为空")
    @Pattern(regexp = "[a-zA-Z0-9_.-]{2,100}", message = "配置键只能包含字母、数字、点、下划线与短横线")
    @Size(max = 100, message = "配置键不能超过 100 字")
    private String configKey;

    @NotBlank(message = "配置值不能为空")
    private String configValue;

    /** 1 字符串 / 2 整数 / 3 布尔 / 4 JSON */
    private Integer valueType;

    @Size(max = 50, message = "分组不能超过 50 字")
    private String configGroup;

    @Size(max = 200, message = "描述不能超过 200 字")
    private String description;
}
