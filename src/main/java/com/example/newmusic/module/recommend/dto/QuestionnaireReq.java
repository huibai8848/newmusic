package com.example.newmusic.module.recommend.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("冷启动口味问卷")
public class QuestionnaireReq {

    @Size(max = 3, message = "音乐风格最多选 3 个")
    private List<String> styles;

    @Size(max = 6, message = "语种最多选 6 个")
    private List<String> languages;

    @Size(max = 5, message = "歌手最多选 5 个")
    private List<Long> artists;

    @ApiModelProperty("FOCUS/RELAX/SPORT/SAD/JUST_LISTEN")
    private String mood;
}
