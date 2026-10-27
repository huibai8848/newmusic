package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 播放进度读取（模块 12 FR-21-007 `GET /play/progress`）。
 *
 * <p>跨设备续播由前端决定「是否提示」：服务端只把最近一次进度与来源设备原样返回，
 * **绝不自动跳转/自动播放**（BR-21-066）。
 */
@Data
@ApiModel("播放进度")
public class PlayProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long songId;

    @ApiModelProperty("是否找到进度记录")
    private boolean found;

    @ApiModelProperty("播放位置（秒）")
    private double position;

    @ApiModelProperty("歌曲总时长（秒）")
    private double duration;

    @ApiModelProperty("播放历史 ID")
    private Long playId;

    @ApiModelProperty("最近上报该进度的设备")
    private String deviceId;

    @ApiModelProperty("最近更新时间（毫秒时间戳）；服务端时钟，不信任客户端")
    private long updateTime;

    @ApiModelProperty("是否由当前设备上报（同设备不提示续播）")
    private boolean fromCurrentDevice;

    @ApiModelProperty("数据来源 REDIS/DB/NONE")
    private String source;
}
