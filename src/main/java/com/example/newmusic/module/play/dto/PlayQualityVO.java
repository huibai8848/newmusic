package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 单档音质的能力描述（模块 12 `GET /play/qualities`）。
 */
@Data
@ApiModel("音质选项")
public class PlayQualityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("standard/high/lossless")
    private String code;

    @ApiModelProperty("中文名")
    private String label;

    @ApiModelProperty("码率")
    private String bitrate;

    @ApiModelProperty("格式 mp3/flac")
    private String format;

    @ApiModelProperty("是否仅 VIP 可用")
    private boolean vipOnly;

    @ApiModelProperty("当前用户是否可用")
    private boolean available;

    @ApiModelProperty("不可用原因；可用时为 null")
    private String unavailableReason;
}
