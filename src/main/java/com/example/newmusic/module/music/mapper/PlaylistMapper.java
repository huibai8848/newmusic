package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {

    /** 歌单歌曲数增减（冗余字段维护；用 SQL 原子更新避免并发覆盖）。 */
    @Update("UPDATE t_playlist SET song_count = GREATEST(CAST(song_count AS SIGNED) + #{delta}, 0) "
            + "WHERE id = #{playlistId} AND deleted = 0")
    int updateSongCount(@Param("playlistId") Long playlistId, @Param("delta") int delta);
}
