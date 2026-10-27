package com.example.newmusic.module.recommend.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("最近常听歌手")
public class FrequentArtistVO {

    private Long artistId;
    private String artistName;
    private String avatar;
    private Long playCount;
    private LocalDateTime lastPlayTime;
}
