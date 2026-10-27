package com.example.newmusic.module.recommend.vo;

import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel("推荐屏蔽列表")
public class BlocklistVO {

    private List<Song> songs;
    private List<Artist> artists;

    @ApiModelProperty("歌曲屏蔽上限（200）")
    private int songLimit;

    @ApiModelProperty("歌手屏蔽上限（50）")
    private int artistLimit;
}
