package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 可用音质列表（模块 12 `GET /play/qualities`）。
 */
@Data
@ApiModel("可用音质列表")
public class PlayQualitiesVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌曲ID；未传 songId 时为 null（只返回权限维度的可用列表）")
    private Long songId;

    @ApiModelProperty("当前用户是否 VIP")
    private boolean vip;

    @ApiModelProperty("当前用户默认应选的音质")
    private String recommended;

    @ApiModelProperty("音质选项，从高到低")
    private List<PlayQualityVO> qualities = new ArrayList<>();
}
