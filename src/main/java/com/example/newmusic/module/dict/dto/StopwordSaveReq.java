package com.example.newmusic.module.dict.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("停用词保存请求")
public class StopwordSaveReq {

    @NotBlank
    private String word;

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled = 1;
}
