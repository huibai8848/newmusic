package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Album;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AlbumMapper extends BaseMapper<Album> {

    /** 专辑歌曲数增减（冗余字段，由曲库维护）。 */
    @Update("UPDATE t_album SET song_count = GREATEST(CAST(song_count AS SIGNED) + #{delta}, 0) "
            + "WHERE id = #{albumId} AND deleted = 0")
    int updateSongCount(@Param("albumId") Long albumId, @Param("delta") int delta);
}
