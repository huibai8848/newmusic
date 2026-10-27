package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@ApiModel("搜索结果-歌手")
public class SearchArtistVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌手ID")
    private Long id;

    @ApiModelProperty("歌手名")
    private String name;

    @ApiModelProperty("头像")
    private String cover;

    @ApiModelProperty("歌曲数")
    private Integer songCount;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    @ApiModelProperty("相关性得分")
    private Integer score;

    @ApiModelProperty("命中字段")
    private List<String> matchedFields;

    @ApiModelProperty("高亮片段（已转义，前端用 dangerouslySetInnerHTML 渲染）")
    private Map<String, String> highlight;
}
