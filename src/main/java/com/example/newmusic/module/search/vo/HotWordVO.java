package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 热搜词（§5.4）。
 *
 * <p><b>没有搜索量字段，这是刻意的</b>（BR-15-034）：把「12345 人在搜」摆到用户面前，
 * 等于告诉刷榜的人「再刷 100 次就能进前三」。真实数值只落在
 * {@code t_search_hot.search_count} 里给运营看。
 */
@Data
@ApiModel("热搜词")
public class HotWordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("词")
    private String word;

    @ApiModelProperty("排名，从 1 开始")
    private Integer rank;

    @ApiModelProperty("标记：热 / 新 / 爆 / null（BR-15-033）")
    private String mark;

    @ApiModelProperty("是否运营置顶（BR-15-030）")
    private Boolean isTop;
}
