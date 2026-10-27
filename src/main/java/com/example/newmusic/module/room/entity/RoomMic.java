package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 房间麦位 t_room_mic。1 号麦为房主专属（BR-14-010 / EX-14-005-6）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room_mic")
public class RoomMic extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_OCCUPIED = 1;
    public static final int STATUS_LOCKED = 2;

    private Long roomId;
    private Integer micPos;
    private Long userId;
    private Integer status;
}
