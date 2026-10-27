package com.example.newmusic.module.recupgrade.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/** 推荐埋点上报（FR-24-009）。 */
@Data
@ApiModel("推荐埋点事件")
public class RecEventRequest {

    @ApiModelProperty(value = "推荐链路 traceId；来自 /recommend/feed 的返回", required = true)
    @NotBlank(message = "traceId 不能为空")
    private String traceId;

    @ApiModelProperty(value = "事件类型", required = true,
            example = "IMPRESSION/CLICK/PLAY/VALID_PLAY/COMPLETE/SKIP/LIKE/DISLIKE")
    @NotBlank(message = "eventType 不能为空")
    private String eventType;

    @ApiModelProperty("歌曲ID")
    private Long songId;

    @ApiModelProperty("在推荐列表中的位置，从 1 开始")
    @Min(1)
    @Max(500)
    private Integer position;

    @ApiModelProperty("播放时长（秒）")
    @Min(0)
    private Integer playDuration;

    @ApiModelProperty("场景；缺省时从 trace 回填")
    private String scene;

    @ApiModelProperty("设备ID；游客必传")
    private String deviceId;

    @ApiModelProperty(value = "设备类型", example = "IOS")
    private String deviceType;

    @ApiModelProperty(value = "用户类型：NEW/ACTIVE/GUEST", example = "ACTIVE")
    private String userType;
}
