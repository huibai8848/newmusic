package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("反馈结果")
public class FeedbackVO {

    private String action;
    private Long songId;
    private Long artistId;

    @ApiModelProperty("当前屏蔽歌曲数")
    private long dislikedSongCount;

    @ApiModelProperty("当前屏蔽歌手数")
    private long dislikedArtistCount;

    @ApiModelProperty("当前喜欢歌曲数")
    private long likedSongCount;
}
