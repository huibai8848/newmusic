package com.example.newmusic.module.recupgrade.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/** 推荐流请求（FR-24-001）。游客可调，此时靠 deviceId 做主体。 */
@Data
@ApiModel("推荐流请求")
public class RecFeedRequest {

    @ApiModelProperty(value = "场景", example = "HOME_FEED")
    private String scene = "HOME_FEED";

    @ApiModelProperty(value = "返回条数 1~100", example = "30")
    @Min(1)
    @Max(100)
    private Integer limit = 30;

    @ApiModelProperty("种子歌曲：从这首歌的相似/同歌手扩散")
    private Long seedSongId;

    @ApiModelProperty("种子歌单")
    private Long seedPlaylistId;

    @ApiModelProperty("设备ID；游客必传，登录用户可空")
    private String deviceId;

    @ApiModelProperty(value = "设备类型", example = "IOS")
    private String deviceType;
}
