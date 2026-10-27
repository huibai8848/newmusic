package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("房间详情")
public class RoomDetailVO extends RoomVO {

    private static final long serialVersionUID = 1L;

    private List<RoomMemberVO> members = new ArrayList<>();
    private List<MicVO> mics = new ArrayList<>();
    private List<QueueItemVO> queue = new ArrayList<>();

    @ApiModelProperty("当前用户角色：-1游客 0成员 1房管 2房主 9黑名单")
    private Integer myRole;

    @ApiModelProperty("当前用户可执行的动作，前端据此渲染按钮")
    private Set<String> myPermissions = new LinkedHashSet<>();

    @ApiModelProperty("当前用户禁言剩余秒数")
    private Long myMuteRemainingSeconds;

    @ApiModelProperty("被踢冷却剩余秒数")
    private Long kickedRemainingSeconds;

    private LocalDateTime serverTime;
}
