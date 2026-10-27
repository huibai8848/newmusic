package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("跳过投票")
public class RoomVoteVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long voteId;
    private Long roomId;
    private Long songId;
    private String songName;
    private Long initiatorId;
    private String initiatorNickname;

    @ApiModelProperty("发起时的在线人数快照")
    private Integer onlineCount;

    private Integer agreeCount;

    @ApiModelProperty("1进行中 2通过 3未通过 4取消")
    private Integer status;

    private Long startTime;
    private Long endTime;

    @ApiModelProperty("剩余秒数")
    private Long remainingSeconds;

    @ApiModelProperty("当前用户是否已投票")
    private Boolean voted;

    @ApiModelProperty("是否通过")
    private Boolean passed;
}
