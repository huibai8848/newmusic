package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.FeedInbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedInboxMapper extends BaseMapper<FeedInbox> {

    @Insert("INSERT IGNORE INTO t_feed_inbox (id, user_id, post_id, author_id, score, create_time) "
            + "VALUES (#{id}, #{userId}, #{postId}, #{authorId}, #{score}, #{createTime})")
    int insertIgnore(@Param("id") Long id, @Param("userId") Long userId, @Param("postId") Long postId,
                     @Param("authorId") Long authorId, @Param("score") long score,
                     @Param("createTime") LocalDateTime createTime);

    @Select("SELECT post_id FROM t_feed_inbox WHERE user_id = #{userId} "
            + "AND (score < #{cursorScore} OR (score = #{cursorScore} AND post_id < #{cursorId})) "
            + "ORDER BY score DESC, post_id DESC LIMIT #{limit}")
    List<Long> selectByCursor(@Param("userId") Long userId, @Param("cursorScore") long cursorScore,
                              @Param("cursorId") Long cursorId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_feed_inbox WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    @Delete("DELETE FROM t_feed_inbox WHERE user_id = #{userId} AND post_id NOT IN ("
            + "SELECT post_id FROM (SELECT post_id FROM t_feed_inbox WHERE user_id = #{userId} "
            + "ORDER BY score DESC, post_id DESC LIMIT #{keep}) x)")
    int trim(@Param("userId") Long userId, @Param("keep") int keep);
}
