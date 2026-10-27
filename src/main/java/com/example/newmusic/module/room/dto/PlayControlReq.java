package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 房间播放控制请求（FR-22-001）。
 *
 * <p>HTTP 与 WebSocket 的语义保持一致：{@code action} 取
 * {@code play/pause/seek/switch/buffering/end}，位置统一用**毫秒**。
 */
@Data
@ApiModel("房间播放控制请求")
public class PlayControlReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "动作：play/pause/seek/switch/buffering/end", required = true)
    @NotBlank(message = "action 不能为空")
    private String action;

    @ApiModelProperty("切歌时的歌曲ID")
    private Long songId;

    @ApiModelProperty("目标位置（毫秒）")
    private Long positionMs;

    @ApiModelProperty("房间音量 0-100，缺省不改")
    private Integer volume;
}
