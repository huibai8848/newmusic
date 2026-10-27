package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel("首页聚合推荐")
public class HomeRecommendVO {

    private DailyRecommendVO daily;
    private GuessRecommendVO guess;
    private List<ContinuePlayVO> continuePlay;
    private List<FrequentArtistVO> frequentArtists;

    @ApiModelProperty("行为数不足 5 条，前端可展示口味问卷入口")
    private boolean coldStart;

    @ApiModelProperty("单个区块失败时的降级说明（例如 guess=hot）")
    private List<String> degraded;
}
