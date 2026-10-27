package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 房间成员 t_room_member。游客不落这张表（BR-14-014）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room_member")
@ApiModel("房间成员")
public class RoomMember extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int ROLE_MEMBER = 0;
    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_OWNER = 2;
    public static final int ROLE_BLACKLIST = 9;

    private Long roomId;
    private Long userId;

    @ApiModelProperty("0成员 1房管 2房主 9黑名单")
    private Integer roomRole;

    @ApiModelProperty("0正常 1禁言中")
    private Integer muteStatus;

    private LocalDateTime muteUntil;
    private LocalDateTime joinTime;

    /** 本次在线开始时间；离开后清空，因此它是“停留时长”的幂等依据。 */
    private LocalDateTime lastEnterTime;

    private LocalDateTime leaveTime;

    @ApiModelProperty("累计停留秒数")
    private Long totalDuration;

    @ApiModelProperty("贡献值")
    private Integer contribute;
}
