package com.example.newmusic.module.play.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 播放历史 t_play_history（PRD 16 FR-16-004）。
 *
 * <p>模块 07 首版把历史放在 localStorage（系分 07 §4 偏差项）；模块 08 的个性化推荐
 * 必须有服务端行为数据，因此在这里落地最小可用的服务端历史。字段与 PRD 的
 * {@code t_play_history} 对齐，额外冗余了歌名/歌手/流派/语种快照，避免推荐聚合时
 * 每次 JOIN 曲库，也让歌曲下架后历史仍可展示（BR-16-033）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_play_history")
@ApiModel("播放历史")
public class PlayHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("用户ID；游客为 null")
    private Long userId;

    @ApiModelProperty("游客设备标识")
    private String deviceId;

    private Long songId;
    private String songName;
    private Long artistId;
    private String artistName;
    private String genre;
    private String language;

    @ApiModelProperty("1标准 2高清 3无损")
    private Integer quality;

    @ApiModelProperty("实际播放秒数")
    private Integer playDuration;

    @ApiModelProperty("歌曲总时长（秒）")
    private Integer songDuration;

    @ApiModelProperty("最近播放进度（秒）")
    private BigDecimal lastPosition;

    @ApiModelProperty("是否有效播放：≥30 秒或 ≥60%")
    private Integer isValid;

    @ApiModelProperty("来源 search/playlist/album/artist/home/room/recommend")
    private String source;

    private Long roomId;

    private LocalDateTime playTime;
}
