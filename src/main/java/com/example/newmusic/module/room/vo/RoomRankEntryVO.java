package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("房间榜单条目")
public class RoomRankEntryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer rank;
    private Long userId;
    private String nickname;
    private String avatar;

    @ApiModelProperty("榜单分值：礼物价值 / 活跃分 / 点歌次数 / 收听秒数")
    private Long score;
}
