package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("新增/编辑敏感词")
public class SensitiveWordSaveReq {

    @NotBlank(message = "词条不能为空")
    @Size(max = 128, message = "词条最长 128 字")
    private String word;

    @ApiModelProperty("1疑似 2高危 3禁止")
    @Min(value = 1, message = "级别只能为 1/2/3")
    @Max(value = 3, message = "级别只能为 1/2/3")
    private Integer level;

    @Size(max = 32)
    private String category;

    @Size(max = 32)
    private String source;

    @Size(max = 256)
    private String remark;

    @ApiModelProperty("0停用 1启用，默认 1")
    private Integer status;
}
