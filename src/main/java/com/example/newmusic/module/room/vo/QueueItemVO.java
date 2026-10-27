package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@ApiModel("点歌/排麦队列项")
public class QueueItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long roomId;
    private Long userId;
    private String nickname;
    private String avatar;
    private Long songId;
    private String songName;
    private String artistName;
    private String cover;
    private Integer duration;

    @ApiModelProperty("1排麦 2播放队列")
    private Integer queueType;

    @ApiModelProperty("0待审 1已通过 2已拒绝 3已播放 4已移除")
    private Integer status;

    private Long seq;
    private Integer source;

    @ApiModelProperty("优先级：0普通 100房主置顶")
    private Integer priority;

    @ApiModelProperty("重复点歌合并的投票数")
    private Integer voteCount;

    @ApiModelProperty("排序索引，越大越靠前")
    private Long orderIndex;

    private LocalDateTime createTime;
}
