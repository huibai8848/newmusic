package com.example.newmusic.module.recommend.vo;

import com.example.newmusic.module.music.entity.Song;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("继续播放")
public class ContinuePlayVO {

    private Song song;

    @ApiModelProperty("断点位置（秒）")
    private double position;

    @ApiModelProperty("歌曲总时长（秒）")
    private double duration;

    @ApiModelProperty("0~1 的播放进度")
    private double progress;
}
