package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** 埋点定义新增/修改（FR-26-001）。 */
@Data
@ApiModel("埋点定义保存")
public class EventDefSaveReq {

    @ApiModelProperty(value = "事件名", required = true, example = "player_song_play")
    @NotBlank(message = "eventName 不能为空")
    private String eventName;

    @ApiModelProperty(value = "中文名", required = true)
    @NotBlank(message = "eventCnName 不能为空")
    private String eventCnName;

    @ApiModelProperty("域：user/player/search/recommend/social/room/ai/track/data")
    private String domain;

    @ApiModelProperty("触发时机")
    private String triggerDesc;

    @ApiModelProperty("业务属性 JSON Schema")
    private String properties;

    @ApiModelProperty("1 核心 2 重要 3 辅助")
    private Integer priority;

    @ApiModelProperty("是否采样")
    private Boolean sampled;

    @ApiModelProperty("采样率 0~1")
    private BigDecimal sampleRate;

    private String owner;

    @ApiModelProperty("版本，默认 v1")
    private String version;

    @ApiModelProperty("0 草稿 1 生效 2 废弃")
    private Integer status;
}
