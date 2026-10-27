package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel("相似歌曲")
public class SimilarRecommendVO {

    private Long songId;
    private boolean fallback;
    private List<RecommendItemVO> items;
}
