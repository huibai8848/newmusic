package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 房间投票切歌记录 t_room_vote（模块 13 FR-22-004）。
 *
 * <p>Redis 是实时计票来源，DB 是结果与分析来源。唯一键
 * {@code uk_vote_room_song(room_id, song_id)} 保证「同一首歌只能发起一次投票」，
 * 即使 Redis 键过期或被清空也不会重新放行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room_vote")
public class RoomVote extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_PASSED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_CANCELLED = 4;

    private Long roomId;
    private Long songId;
    private Long initiatorId;
    private Integer onlineCount;
    private Integer agreeCount;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
