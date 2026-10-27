package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@ApiModel("搜索结果-歌单")
public class SearchPlaylistVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌单ID")
    private Long id;

    @ApiModelProperty("歌单名")
    private String name;

    @ApiModelProperty("封面")
    private String cover;

    @ApiModelProperty("曲目数")
    private Integer songCount;

    @ApiModelProperty("播放数")
    private Long playCount;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    @ApiModelProperty("相关性得分")
    private Integer score;

    @ApiModelProperty("命中字段")
    private List<String> matchedFields;

    @ApiModelProperty("高亮片段（已转义，前端用 dangerouslySetInnerHTML 渲染）")
    private Map<String, String> highlight;
}
