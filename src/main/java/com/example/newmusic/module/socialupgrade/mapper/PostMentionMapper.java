package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.PostMention;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PostMentionMapper extends BaseMapper<PostMention> {

    @Select("SELECT * FROM t_post_mention WHERE source_type = #{sourceType} AND source_id = #{sourceId} "
            + "ORDER BY offset ASC")
    List<PostMention> selectBySource(@Param("sourceType") int sourceType,
                                     @Param("sourceId") Long sourceId);

    @Select("SELECT COUNT(*) FROM t_post_mention WHERE author_id = #{authorId} AND create_time >= #{since}")
    int countByAuthorSince(@Param("authorId") Long authorId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM t_post_mention WHERE author_id = #{authorId} "
            + "AND mentioned_user_id = #{targetId} AND create_time >= #{since}")
    int countPairSince(@Param("authorId") Long authorId, @Param("targetId") Long targetId,
                       @Param("since") LocalDateTime since);

    @Select("SELECT mentioned_user_id FROM t_post_mention WHERE author_id = #{authorId} "
            + "GROUP BY mentioned_user_id ORDER BY MAX(create_time) DESC LIMIT #{limit}")
    List<Long> selectRecentTargets(@Param("authorId") Long authorId, @Param("limit") int limit);
}
