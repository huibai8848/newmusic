package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel("猜你喜欢")
public class GuessRecommendVO {

    private int page;
    private int pageSize;
    private boolean hasNext;
    private boolean coldStart;
    private List<RecommendItemVO> items;
}
