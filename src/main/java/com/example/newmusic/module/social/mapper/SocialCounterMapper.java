package com.example.newmusic.module.social.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 内容表的互动计数更新（模块 10）。
 *
 * <p>统一用 {@code GREATEST(x + delta, 0)}：删除与重复请求可能让 delta 为负，
 * 绝不允许把计数打到负数。
 */
@Mapper
public interface SocialCounterMapper {

    @Update("UPDATE t_song SET like_count = GREATEST(like_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int songLike(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_song SET collect_count = GREATEST(collect_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int songCollect(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_song SET comment_count = GREATEST(comment_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int songComment(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_song SET share_count = GREATEST(share_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int songShare(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_playlist SET like_count = GREATEST(like_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int playlistLike(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_playlist SET collect_count = GREATEST(collect_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int playlistCollect(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_playlist SET comment_count = GREATEST(comment_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int playlistComment(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_playlist SET share_count = GREATEST(share_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int playlistShare(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_album SET like_count = GREATEST(like_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int albumLike(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_album SET collect_count = GREATEST(collect_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int albumCollect(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_album SET comment_count = GREATEST(comment_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int albumComment(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_album SET share_count = GREATEST(share_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int albumShare(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_artist SET follower_count = GREATEST(follower_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int artistFollower(@Param("id") Long id, @Param("delta") int delta);
}
