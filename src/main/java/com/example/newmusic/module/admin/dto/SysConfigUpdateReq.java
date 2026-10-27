package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
@ApiModel("修改系统配置")
public class SysConfigUpdateReq {

    /** 允许空字符串（清空字符串型配置），但不允许缺字段 */
    @NotNull(message = "配置值不能为空（如需清空请传空字符串）")
    private String configValue;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
