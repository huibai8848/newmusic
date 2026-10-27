package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 投票明细 t_room_vote_detail。实时判定用 Redis Set，落库仅用于分析与审计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room_vote_detail")
public class RoomVoteDetail extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long voteId;
    private Long roomId;
    private Long userId;
    private Integer agree;
}
