package com.example.newmusic.module.dict.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("拼音词典保存请求")
public class PinyinSaveReq {

    @NotBlank
    @ApiModelProperty(value = "词，如 重庆", required = true)
    private String word;

    @NotBlank
    @ApiModelProperty(value = "无声调小写拼音，如 chongqing", required = true)
    private String pinyin;

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled = 1;
}
