package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@ApiModel("冷启动口味问卷")
public class QuestionnaireVO {

    private List<String> styleOptions;
    private List<String> languageOptions;

    @ApiModelProperty("mood code → 展示文案")
    private Map<String, String> moodOptions;

    private PreferenceVO current;

    @Data
    @ApiModel("已保存的偏好")
    public static class PreferenceVO {
        private List<String> styles;
        private List<String> languages;
        private List<Long> artists;
        private String mood;
    }
}
