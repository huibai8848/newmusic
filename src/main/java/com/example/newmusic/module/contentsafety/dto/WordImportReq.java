package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
@ApiModel("批量导入敏感词")
public class WordImportReq {

    @ApiModelProperty("每行一个词；支持 # 注释行")
    @NotBlank(message = "导入内容不能为空")
    private String content;

    @ApiModelProperty("1疑似 2高危 3禁止")
    @Min(value = 1, message = "级别只能为 1/2/3")
    @Max(value = 3, message = "级别只能为 1/2/3")
    private Integer level;

    private String category;
    private String source;
    private String remark;
}
