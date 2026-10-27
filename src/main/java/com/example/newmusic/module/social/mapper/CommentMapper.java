package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    @Select("SELECT COUNT(*) FROM t_comment WHERE user_id = #{userId} AND create_time >= #{since}")
    int countSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM t_comment WHERE user_id = #{userId} AND target_type = #{targetType} "
            + "AND target_id = #{targetId} AND content = #{content} AND create_time >= #{since}")
    int countSameContent(@Param("userId") Long userId,
                         @Param("targetType") String targetType,
                         @Param("targetId") Long targetId,
                         @Param("content") String content,
                         @Param("since") LocalDateTime since);

    @Update("UPDATE t_comment SET like_count = GREATEST(like_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int updateLikeCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_comment SET reply_count = GREATEST(reply_count + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int updateReplyCount(@Param("id") Long id, @Param("delta") int delta);

    /** 单条评论软删：内容抹空，保留行用于计数与审计。 */
    @Update("UPDATE t_comment SET status = 2, deleted = 1, content = '', update_time = NOW(3) "
            + "WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id);

    /** 级联隐藏回复：根评论删除后其下回复不再展示。 */
    @Update("UPDATE t_comment SET status = 2, deleted = 1, content = '', update_time = NOW(3) "
            + "WHERE root_id = #{rootId} AND deleted = 0")
    int softDeleteReplies(@Param("rootId") Long rootId);

    @Select("SELECT COUNT(*) FROM t_comment WHERE root_id = #{rootId} AND deleted = 0")
    int countReplies(@Param("rootId") Long rootId);

    @Select("SELECT COUNT(*) FROM t_comment WHERE root_id = #{rootId} AND status = 1 AND deleted = 0")
    int countPublishedReplies(@Param("rootId") Long rootId);

    /** 每个根评论取前 N 条回复（窗口函数，避免列表页 N+1）。 */
    @Select("<script>SELECT * FROM ("
            + " SELECT c.*, ROW_NUMBER() OVER (PARTITION BY c.root_id ORDER BY c.create_time ASC, c.id ASC) rn"
            + " FROM t_comment c WHERE c.root_id IN "
            + "<foreach collection='rootIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND c.status = 1 AND c.deleted = 0"
            + ") t WHERE t.rn &lt;= #{perRoot}"
            + "</script>")
    java.util.List<Comment> selectFirstReplies(@Param("rootIds") java.util.List<Long> rootIds,
                                               @Param("perRoot") int perRoot);
}
