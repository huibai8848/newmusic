package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.PostLike;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {

    @Insert("INSERT IGNORE INTO t_post_like (id, user_id, post_id, create_time) "
            + "VALUES (#{id}, #{userId}, #{postId}, #{createTime})")
    int insertIgnore(@Param("id") Long id, @Param("userId") Long userId,
                     @Param("postId") Long postId, @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_post_like WHERE user_id = #{userId} AND post_id = #{postId}")
    int deletePair(@Param("userId") Long userId, @Param("postId") Long postId);

    @Select("<script>SELECT post_id FROM t_post_like WHERE user_id = #{userId} AND post_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectLikedIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Select("SELECT COUNT(*) FROM t_post_like WHERE post_id = #{postId}")
    int countByPost(@Param("postId") Long postId);

    @Select("SELECT post_id FROM t_post_like WHERE user_id = #{userId} "
            + "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<Long> selectLikedPostIds(@Param("userId") Long userId, @Param("offset") long offset,
                                  @Param("size") int size);

    @Select("SELECT COUNT(*) FROM t_post_like WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    @Select("SELECT user_id FROM t_post_like WHERE post_id = #{postId} ORDER BY create_time ASC LIMIT 1")
    Long selectFirstLiker(@Param("postId") Long postId);
}
