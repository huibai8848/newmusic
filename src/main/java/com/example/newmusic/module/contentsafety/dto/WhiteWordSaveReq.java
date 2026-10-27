package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("新增白名单")
public class WhiteWordSaveReq {

    @NotBlank(message = "白名单词不能为空")
    @Size(max = 128)
    private String word;

    @Size(max = 256)
    private String reason;
}
