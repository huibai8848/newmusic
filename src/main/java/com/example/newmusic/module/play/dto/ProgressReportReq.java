package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("上报播放进度")
public class ProgressReportReq {

    @NotNull(message = "songId 不能为空")
    private Long songId;

    private Long playId;

    @NotNull(message = "position 不能为空")
    @Min(value = 0, message = "position 不能为负数")
    private Double position;

    @ApiModelProperty("歌曲总时长（秒）")
    private Double duration;

    @ApiModelProperty("设备标识；登录用户传当前 deviceId，用于跨设备续播")
    private String deviceId;

    @ApiModelProperty("设备名，如 Chrome on Windows")
    private String deviceName;

    @ApiModelProperty("设备类型 WEB/PWA/MOBILE_WEB")
    private String deviceType;
}
