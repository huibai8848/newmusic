package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 播放地址响应（PRD 16 §6.1）。
 */
@Data
@ApiModel("播放地址")
public class PlayUrlVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌曲ID")
    private Long songId;

    @ApiModelProperty("实际下发的音质 standard/high/lossless")
    private String quality;

    @ApiModelProperty("请求的音质（可能与 quality 不同，见 downgraded）")
    private String requestedQuality;

    @ApiModelProperty("是否降级：请求无损但只有标准档时为 true")
    private boolean downgraded;

    @ApiModelProperty("降级原因 NO_SOURCE（无该档音源）/ VIP_REQUIRED（非 VIP 请求无损）/ "
            + "LOGIN_REQUIRED（游客请求高于标准音质）；未降级为 null")
    private String downgradeReason;

    @ApiModelProperty("可直接展示给用户的降级说明；未降级为 null")
    private String notice;

    @ApiModelProperty(value = "带签名的播放地址", notes = "相对路径或绝对 URL 取决于 newmusic.play.cdn-base；前端原样使用，不做拼接")
    private String url;

    @ApiModelProperty("时长（秒）")
    private Integer duration;

    @ApiModelProperty("音频格式 mp3/wav/flac")
    private String format;

    @ApiModelProperty("过期时间（毫秒时间戳）")
    private Long expiresAt;

    @ApiModelProperty(value = "试听片段时长（秒）", notes = "游客试听属模块 03/12，本次恒为 null")
    private Integer previewDuration;
}
