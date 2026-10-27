package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 漏斗查询（支持任意步骤组合，BR-26-061）。 */
@Data
@ApiModel("漏斗查询")
public class FunnelReq {

    @ApiModelProperty("漏斗名称")
    private String name;

    @ApiModelProperty(value = "统计开始日期 yyyy-MM-dd，默认 7 天前")
    private String start;

    @ApiModelProperty(value = "统计结束日期 yyyy-MM-dd，默认今天")
    private String end;

    @ApiModelProperty(value = "步骤列表，按先后顺序", required = true)
    private List<Step> steps;

    @ApiModelProperty("归因窗口（小时），默认 168（7 天）")
    private Integer windowHours;

    @ApiModelProperty("分维度：platform/os/network/region")
    private String dimension;

    @ApiModelProperty("维度取值；为空表示全量")
    private String dimensionValue;

    @ApiModelProperty("按 userId 还是 deviceId 统计：user/device，默认 user")
    private String subject;

    @Data
    @ApiModel("漏斗步骤")
    public static class Step {
        @ApiModelProperty(value = "事件名", required = true, example = "player_song_play")
        private String eventName;
        @ApiModelProperty("步骤展示名")
        private String label;
        @ApiModelProperty("属性过滤，如 {\"song_id\":\"1001\"}；支持 >=/<=/>/< 前缀做数值比较；值为空表示不限制")
        private Map<String, String> filters;
        @ApiModelProperty("该步骤需累计发生次数（如「有效播放 ≥ 3 首」填 3），默认 1")
        private Integer minCount;
    }
}
