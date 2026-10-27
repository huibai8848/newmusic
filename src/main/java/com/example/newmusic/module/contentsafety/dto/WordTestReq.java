package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("词库测试")
public class WordTestReq {

    @NotBlank(message = "请输入待检测文本")
    @Size(max = 10000, message = "文本最长 10000 字")
    private String text;

    private String scene;
}
