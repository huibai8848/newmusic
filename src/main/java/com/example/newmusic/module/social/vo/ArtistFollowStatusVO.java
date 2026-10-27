package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel("歌手关注状态")
public class ArtistFollowStatusVO {

    private Long artistId;

    private Boolean followed;

    private Long followerCount;
}
