package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 播放设置更新（模块 12 FR-21-008）。
 *
 * <p>全部字段可空，语义是「只改传了的字段」——与模块 10 的通知/隐私设置一致。
 * 取值范围由 {@code PlaySettingSupport.apply} 统一校验，非法值返回 30061。
 */
@Data
@ApiModel("更新播放设置")
public class PlaySettingReq {

    @ApiModelProperty("standard/high/lossless")
    private String defaultQuality;

    @ApiModelProperty("order/loop/single/shuffle")
    private String playMode;

    @ApiModelProperty("自动降质 0关 1开")
    private Integer autoQuality;

    @ApiModelProperty("省流量模式 0关 1开")
    private Integer dataSaver;

    @ApiModelProperty("淡入淡出（P2 预留）")
    private Integer fadeEnabled;

    @ApiModelProperty("均衡器预设（P2 预留）")
    private String equalizerPreset;

    @ApiModelProperty("自定义 EQ 频段 JSON（P2 预留）")
    private String equalizerBands;

    @ApiModelProperty("0.50/0.75/1.00/1.25/1.50/1.75/2.00")
    private BigDecimal playbackRate;

    @ApiModelProperty("歌词显示模式")
    private String lyricMode;

    @ApiModelProperty("全局歌词偏移（毫秒），-5000~5000")
    private Integer lyricOffset;

    @ApiModelProperty("system/light/dark")
    private String theme;

    @ApiModelProperty("自动下载（P2 预留）")
    private Integer autoDownload;
}
