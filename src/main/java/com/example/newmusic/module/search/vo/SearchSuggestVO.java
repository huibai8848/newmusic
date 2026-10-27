package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@ApiModel("搜索联想结果")
public class SearchSuggestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("建议列表，按 歌手 > 歌曲 > 专辑 > 歌单 分组（BR-15-016）")
    private List<SuggestItemVO> suggestions;

    /**
     * 热搜兜底词。
     *
     * <p>BR-15-021：联想**无结果**时给热搜词，而不是给一个空下拉——
     * 空下拉会让用户以为「输入没生效」，热搜词至少给了几个可以点的方向。
     */
    @ApiModelProperty("热搜兜底词")
    private List<String> hotWords;
}
