package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.ArtistFollow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface ArtistFollowMapper extends BaseMapper<ArtistFollow> {

    @Insert("INSERT IGNORE INTO t_user_artist_follow (id, user_id, artist_id, create_time) "
            + "VALUES (#{id}, #{userId}, #{artistId}, #{createTime})")
    int insertIgnore(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("artistId") Long artistId,
                     @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_user_artist_follow WHERE user_id = #{userId} AND artist_id = #{artistId}")
    int deleteByPair(@Param("userId") Long userId, @Param("artistId") Long artistId);

    @Select("SELECT COUNT(*) FROM t_user_artist_follow WHERE artist_id = #{artistId}")
    long countByArtist(@Param("artistId") Long artistId);
}
