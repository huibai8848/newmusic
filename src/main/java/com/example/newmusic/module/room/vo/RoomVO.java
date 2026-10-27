package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@ApiModel("房间卡片/详情")
public class RoomVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @ApiModelProperty("6位数字房间号")
    private String roomNo;

    private String name;
    private String cover;
    private Long ownerId;
    private String ownerNickname;
    private String ownerAvatar;

    @ApiModelProperty("1公开 2私密 3密码")
    private Integer roomType;

    @ApiModelProperty("是否需要密码")
    private Boolean passwordRequired;

    @ApiModelProperty("公告")
    private String notice;

    private String themeTags;
    private Integer micCount;
    private Integer maxMembers;

    @ApiModelProperty("1房主控播 2自由播放 3投票切歌")
    private Integer playMode;

    private Integer needApprove;
    private Integer allowFreeMic;

    @ApiModelProperty("1活跃 2已关闭 3已解散")
    private Integer status;

    @ApiModelProperty("实时在线人数（Redis）")
    private Integer onlineCount;

    private Integer hotScore;
    private String currentSongId;
    private String currentSongName;
    private String currentArtistName;
    private String currentCover;
    private Integer currentDuration;
    private Long playedPosition;
    private Boolean playing;

    private LocalDateTime createTime;
    private LocalDateTime lastActiveTime;
}
