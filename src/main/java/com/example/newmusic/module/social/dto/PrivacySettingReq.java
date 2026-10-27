package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
@ApiModel("隐私设置")
public class PrivacySettingReq {

    @ApiModelProperty("0所有人 1互关 2关闭")
    @Min(0) @Max(2)
    private Integer messagePolicy;

    @ApiModelProperty("0所有人 1关注我的人 2关闭")
    @Min(0) @Max(2)
    private Integer commentPolicy;

    @ApiModelProperty("0所有人 1关注我的人 2关闭")
    @Min(0) @Max(2)
    private Integer mentionPolicy;

    @Min(0) @Max(1)
    private Integer showFollowing;

    @Min(0) @Max(1)
    private Integer showFollowers;

    @ApiModelProperty("0所有人 1互关 2仅自己")
    @Min(0) @Max(2)
    private Integer playHistoryVisibility;

    @Min(0) @Max(1)
    private Integer readReceipt;

    @Min(0) @Max(1)
    private Integer newSongNotify;

    @Min(0) @Max(1)
    private Integer systemNotify;
}
