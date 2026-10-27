package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.PlaylistSong;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlaylistSongMapper extends BaseMapper<PlaylistSong> {

    /** 下一个排序号（当前最大值 + 步长）。 */
    @Select("SELECT IFNULL(MAX(sort_order), 0) + #{step} FROM t_playlist_song WHERE playlist_id = #{playlistId}")
    int nextSortOrder(@Param("playlistId") Long playlistId, @Param("step") int step);

    /** 排序号复位（步长 100 整体重排用）。 */
    @Update("UPDATE t_playlist_song SET sort_order = #{sortOrder} WHERE playlist_id = #{playlistId} AND song_id = #{songId}")
    int updateSortOrder(@Param("playlistId") Long playlistId, @Param("songId") Long songId,
                        @Param("sortOrder") Integer sortOrder);
}
