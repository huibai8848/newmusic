package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel("上报播放开始")
public class RecordPlayReq {

    @NotNull(message = "songId 不能为空")
    private Long songId;

    @ApiModelProperty("standard/high/lossless")
    private String quality;

    @ApiModelProperty("search/playlist/album/artist/home/room/recommend/queue")
    private String source;

    private Long roomId;

    @ApiModelProperty("游客设备标识；登录用户可不传")
    private String deviceId;

    @ApiModelProperty("设备名，如 Chrome on Windows")
    private String deviceName;

    @ApiModelProperty("设备类型 WEB/PWA/MOBILE_WEB")
    private String deviceType;

    @ApiModelProperty("客户端生成的播放会话 ID；不传则使用服务端历史 ID")
    private String playId;
}
