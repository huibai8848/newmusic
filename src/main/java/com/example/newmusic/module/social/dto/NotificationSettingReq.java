package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
@ApiModel("通知设置")
public class NotificationSettingReq {

    @Min(0) @Max(1)
    private Integer likeEnabled;

    @Min(0) @Max(1)
    private Integer commentEnabled;

    @Min(0) @Max(1)
    private Integer followEnabled;

    @Min(0) @Max(1)
    private Integer messageEnabled;

    @Min(0) @Max(1)
    private Integer systemEnabled;

    @Min(0) @Max(1)
    private Integer newSongEnabled;

    @Min(0) @Max(1)
    private Integer readReceiptEnabled;
}
