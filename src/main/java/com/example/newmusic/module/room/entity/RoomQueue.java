package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 房间排队记录 t_room_queue。
 *
 * <p>两种用途共用一张表：{@link #QUEUE_MIC} 排麦、{@link #QUEUE_PLAY} 点歌。
 * PRD 的 {@code ml:room:queue:{roomId}} 是播放队列的实时快照，DB 是权威（系分 05 D-08）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room_queue")
public class RoomQueue extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_MIC = 1;
    public static final int TYPE_PLAY = 2;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_PLAYED = 3;
    public static final int STATUS_REMOVED = 4;
    /** 模块 13：被投票/房主跳过的队列项（与“已播放”区分，用于回顾统计） */
    public static final int STATUS_SKIPPED = 5;

    public static final int SOURCE_OWNER = 1;
    public static final int SOURCE_MEMBER = 2;
    public static final int SOURCE_MIC = 3;
    public static final int SOURCE_RECOMMEND = 4;

    private Long roomId;
    private Long userId;
    private Long songId;
    private String songName;
    private String artistName;
    private String cover;
    private Integer duration;

    @ApiModelProperty("1排麦 2播放队列")
    private Integer queueType;

    @ApiModelProperty("0待审 1已通过 2已拒绝 3已播放 4已移除")
    private Integer status;

    @ApiModelProperty("同房间同类型内 FIFO 序号")
    private Long seq;

    @ApiModelProperty("1房主 2成员点歌 3排麦 4系统推荐")
    private Integer source;

    @ApiModelProperty("优先级：0普通 100房主置顶")
    private Integer priority;

    @ApiModelProperty("重复点歌合并的投票数")
    private Integer voteCount;

    @ApiModelProperty("排序索引，越大越靠前")
    private Long orderIndex;
}
