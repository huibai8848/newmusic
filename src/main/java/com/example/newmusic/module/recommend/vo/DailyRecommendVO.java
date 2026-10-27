package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel("每日推荐")
public class DailyRecommendVO {

    @ApiModelProperty("推荐所属日期 yyyy-MM-dd")
    private String date;

    @ApiModelProperty("是否走了热门兜底（EX-17-001-1）")
    private boolean fallback;

    @ApiModelProperty("生成时间戳")
    private long generatedAt;

    @ApiModelProperty("固定 30 首；曲库不足时允许少于 30（EX-17-001-4）")
    private List<RecommendItemVO> items;
}
