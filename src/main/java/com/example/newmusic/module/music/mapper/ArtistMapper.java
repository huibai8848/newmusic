package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Artist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArtistMapper extends BaseMapper<Artist> {

    /** 歌手歌曲数增减（冗余字段，由曲库维护）。 */
    @Update("UPDATE t_artist SET song_count = GREATEST(CAST(song_count AS SIGNED) + #{delta}, 0) "
            + "WHERE id = #{artistId} AND deleted = 0")
    int updateSongCount(@Param("artistId") Long artistId, @Param("delta") int delta);

    /** 歌手专辑数增减（冗余字段）。 */
    @Update("UPDATE t_artist SET album_count = GREATEST(CAST(album_count AS SIGNED) + #{delta}, 0) "
            + "WHERE id = #{artistId} AND deleted = 0")
    int updateAlbumCount(@Param("artistId") Long artistId, @Param("delta") int delta);
}
