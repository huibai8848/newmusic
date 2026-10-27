package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("上报播放结束")
public class EndPlayReq {

    @NotNull(message = "playId 不能为空")
    private Long playId;

    @NotNull(message = "playedDuration 不能为空")
    @Min(value = 0, message = "playedDuration 不能为负数")
    private Integer playedDuration;

    @ApiModelProperty("歌曲总时长（秒）；不传则沿用开始时报的时长")
    private Integer songDuration;

    @ApiModelProperty("结束时的播放位置（秒）。不传时沿用本次已上报的进度")
    private Double position;

    @ApiModelProperty("是否完整播放（前端 ended 事件）")
    private Boolean completed;

    @ApiModelProperty("游客设备标识；登录用户可不传。游客结束上报必须与开始时一致")
    private String deviceId;
}
