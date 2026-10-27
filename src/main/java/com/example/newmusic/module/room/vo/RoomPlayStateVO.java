package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 房间权威播放态（FR-22-001）。
 *
 * <p>新旧字段并存：{@code position/isPlaying} 是模块 04 的秒制字段，
 * {@code startPositionMs/playStatus/playStateVersion} 是模块 13 的毫秒制权威字段。
 * 老客户端只读前者，新客户端只读后者，服务端一次写成两套。
 */
@Data
@ApiModel("房间权威播放态")
public class RoomPlayStateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PLAYING = 1;
    public static final int STATUS_PAUSED = 2;
    public static final int STATUS_BUFFERING = 3;
    public static final int STATUS_ENDED = 4;

    private Long roomId;
    private Long songId;
    private String songName;
    private String artistName;
    private String cover;

    @ApiModelProperty("总时长（毫秒）")
    private Long durationMs;

    @ApiModelProperty("1播放中 2暂停 3缓冲中 4结束")
    private Integer playStatus;

    @ApiModelProperty("本段播放的起始位置（毫秒）")
    private Long startPositionMs;

    @ApiModelProperty("状态更新时的服务端毫秒时间戳")
    private Long stateUpdateTimeMs;

    @ApiModelProperty("单调递增，客户端只应用更大的版本")
    private Long playStateVersion;

    @ApiModelProperty("倍速，房间内固定 100（1.0x）")
    private Integer playbackRate;

    @ApiModelProperty("房间音量 0-100")
    private Integer volume;

    private Long updatedBy;
    private Long serverTime;

    @ApiModelProperty("当前歌曲累计点赞数（BR-22-069，后进房也能看到）")
    private Long likeCount;

    // ---------- 模块 04 兼容字段 ----------
    @ApiModelProperty("兼容字段：当前位置（秒）")
    private Long position;

    @ApiModelProperty("兼容字段：是否播放中")
    private Boolean isPlaying;
}
