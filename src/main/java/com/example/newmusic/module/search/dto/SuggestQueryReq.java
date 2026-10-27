package com.example.newmusic.module.search.dto;

import com.example.newmusic.module.search.config.SearchProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 联想请求参数（§5.3）。
 *
 * <p>不继承 {@code PageQuery}：联想没有页码。它的 {@code limit} 上限是 10（BR-15-015）
 * 而不是通用的 100——下拉框放 100 条既没人看，也把一次前缀全表扫的代价放大十倍。
 */
@Data
@ApiModel("搜索联想请求")
public class SuggestQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("关键词，可为空。空时只返回热搜词（BR-15-019）")
    private String keyword;

    @ApiModelProperty(value = "条数上限，1~10", example = "10")
    private Integer limit;

    /** 归一化：裁到配置上限。上限取「配置值」与「硬上限 10」的较小者 */
    public void normalize(SearchProperties properties) {
        if (keyword != null) {
            keyword = keyword.trim();
        }
        int max = Math.min(properties.getSuggest().getLimit(), 10);
        if (limit == null || limit < 1) {
            limit = max;
        } else if (limit > max) {
            limit = max;
        }
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isEmpty();
    }
}
