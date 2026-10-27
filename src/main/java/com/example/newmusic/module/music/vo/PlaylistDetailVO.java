package com.example.newmusic.module.music.vo;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("歌单详情（含歌曲分页）")
public class PlaylistDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌单主体")
    private Playlist playlist;

    @ApiModelProperty("歌曲分页（按 sort_order 升序）")
    private PageResult<Song> songs;

    public static PlaylistDetailVO of(Playlist playlist, PageResult<Song> songs) {
        PlaylistDetailVO vo = new PlaylistDetailVO();
        vo.setPlaylist(playlist);
        vo.setSongs(songs);
        return vo;
    }
}
