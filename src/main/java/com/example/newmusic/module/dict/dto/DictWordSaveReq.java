package com.example.newmusic.module.dict.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
@ApiModel("主词典词条保存请求")
public class DictWordSaveReq {

    @NotBlank
    @ApiModelProperty(value = "词条，2~15 个字符", required = true)
    private String word;

    @ApiModelProperty(value = "词频，默认 100", example = "100")
    @Min(1)
    @Max(1_000_000)
    private Integer frequency = 100;

    @ApiModelProperty(value = "词性，默认 n", example = "n")
    private String pos = "n";

    @ApiModelProperty(value = "1 启用 0 停用", example = "1")
    private Integer enabled = 1;

    @ApiModelProperty(value = "MAIN / NET_SLANG / REGION", example = "MAIN")
    private String dictType = "MAIN";
}
