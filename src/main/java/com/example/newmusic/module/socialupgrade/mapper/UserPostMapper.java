package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface UserPostMapper extends BaseMapper<UserPost> {

    @Update("UPDATE t_user_post SET like_count = GREATEST(0, like_count + #{delta}) WHERE id = #{id}")
    int incrLike(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_user_post SET comment_count = GREATEST(0, comment_count + #{delta}) WHERE id = #{id}")
    int incrComment(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_user_post SET repost_count = GREATEST(0, repost_count + #{delta}) WHERE id = #{id}")
    int incrRepost(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_user_post SET collect_count = GREATEST(0, collect_count + #{delta}) WHERE id = #{id}")
    int incrCollect(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_user_post SET view_count = view_count + 1 WHERE id = #{id}")
    int incrView(@Param("id") Long id);

    @Update("UPDATE t_user_post SET status = 2, deleted = 1, update_time = NOW(3) WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Update("UPDATE t_user_post SET status = #{status}, audit_status = #{auditStatus}, "
            + "audit_reason = #{reason}, update_time = NOW(3) WHERE id = #{id}")
    int updateAudit(@Param("id") Long id, @Param("status") int status,
                    @Param("auditStatus") int auditStatus, @Param("reason") String reason);

    @Update("UPDATE t_user_post SET image_audit_status = #{status}, update_time = NOW(3) WHERE id = #{id}")
    int updateImageAudit(@Param("id") Long id, @Param("status") int status);

    /** 收件箱构建：关注对象最近的已发布公开动态。 */
    @Select("<script>SELECT * FROM t_user_post WHERE deleted = 0 AND status = 1 AND visibility = 0 "
            + "AND user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND create_time &gt;= #{since} ORDER BY create_time DESC, id DESC LIMIT #{limit}</script>")
    List<UserPost> selectRecentByAuthors(@Param("ids") Collection<Long> ids,
                                         @Param("since") LocalDateTime since,
                                         @Param("limit") int limit);

    /** 全拉降级：按游标（create_time, id）取关注者动态。 */
    @Select("<script>SELECT * FROM t_user_post WHERE deleted = 0 AND status = 1 AND visibility = 0 "
            + "AND user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND (create_time &lt; #{cursorTime} OR (create_time = #{cursorTime} AND id &lt; #{cursorId})) "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}</script>")
    List<UserPost> selectFollowingPull(@Param("ids") Collection<Long> ids,
                                       @Param("cursorTime") LocalDateTime cursorTime,
                                       @Param("cursorId") Long cursorId,
                                       @Param("limit") int limit);

    /** 发现页候选：7 天内已发布的公开动态。 */
    @Select("SELECT * FROM t_user_post WHERE deleted = 0 AND status = 1 AND visibility = 0 "
            + "AND create_time >= #{since} ORDER BY create_time DESC LIMIT #{limit}")
    List<UserPost> selectDiscoverCandidates(@Param("since") LocalDateTime since,
                                            @Param("limit") int limit);

    @Select("SELECT * FROM t_user_post WHERE deleted = 0 AND status = 1 AND visibility = 0 "
            + "AND topics LIKE CONCAT('%', #{topic}, '%') "
            + "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<UserPost> selectByTopic(@Param("topic") String topic, @Param("offset") long offset,
                                 @Param("size") int size);

    @Select("SELECT COUNT(*) FROM t_user_post WHERE deleted = 0 AND status = 1 AND visibility = 0 "
            + "AND topics LIKE CONCAT('%', #{topic}, '%')")
    long countByTopic(@Param("topic") String topic);

    @Select("SELECT COALESCE(SUM(like_count), 0) FROM t_user_post WHERE user_id = #{userId} AND deleted = 0")
    long sumLikeReceived(@Param("userId") Long userId);

    @Select("SELECT * FROM t_user_post WHERE deleted = 0 AND status = 1 "
            + "AND user_id = #{userId} ORDER BY create_time DESC LIMIT #{limit}")
    List<UserPost> selectRecentSelf(@Param("userId") Long userId, @Param("limit") int limit);

    /** 待人工审核：审核中的动态 + 疑似词命中后仅自己可见、尚未被处理的动态。 */
    @Select("SELECT * FROM t_user_post WHERE deleted = 0 "
            + "AND (status = 0 OR (status = 3 AND audit_status = 0)) "
            + "ORDER BY create_time ASC, id ASC LIMIT #{offset}, #{size}")
    List<UserPost> selectPending(@Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM t_user_post WHERE deleted = 0 "
            + "AND (status = 0 OR (status = 3 AND audit_status = 0))")
    long countPending();
}
