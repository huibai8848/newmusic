package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@ApiModel("房间成员")
public class RoomMemberVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String nickname;
    private String avatar;

    @ApiModelProperty("0成员 1房管 2房主 9黑名单")
    private Integer role;

    private Boolean online;
    private Boolean muted;
    private LocalDateTime muteUntil;
    private LocalDateTime joinTime;
    private Long totalDuration;
    private Integer contribute;
}
