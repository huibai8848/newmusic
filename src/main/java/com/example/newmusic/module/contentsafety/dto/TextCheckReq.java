package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("内部文本检测")
public class TextCheckReq {

    @NotBlank(message = "文本不能为空")
    @Size(max = 10000)
    private String text;

    private String scene;
    private String contentType;
    private Long contentId;
    private Long userId;
}
