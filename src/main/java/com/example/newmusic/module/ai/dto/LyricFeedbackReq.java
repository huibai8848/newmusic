package com.example.newmusic.module.ai.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 翻译质量反馈（BR-23-024）。 */
@Data
public class LyricFeedbackReq {

    @NotBlank(message = "请描述翻译问题")
    @Size(max = 200, message = "反馈内容不能超过 200 字")
    private String issue;
}
