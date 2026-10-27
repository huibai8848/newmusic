package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    @Insert("INSERT IGNORE INTO t_follow (id, follower_id, following_id, create_time) "
            + "VALUES (#{id}, #{followerId}, #{followingId}, #{createTime})")
    int insertIgnore(@Param("id") Long id,
                     @Param("followerId") Long followerId,
                     @Param("followingId") Long followingId,
                     @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_follow WHERE follower_id = #{followerId} AND following_id = #{followingId}")
    int deleteByPair(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    @Select("SELECT COUNT(*) FROM t_follow WHERE follower_id = #{followerId}")
    int countFollowing(@Param("followerId") Long followerId);

    @Select("SELECT COUNT(*) FROM t_follow WHERE following_id = #{followingId}")
    int countFollowers(@Param("followingId") Long followingId);

    @Select("SELECT following_id FROM t_follow WHERE follower_id = #{followerId}")
    List<Long> selectFollowingAll(@Param("followerId") Long followerId);

    @Select("SELECT follower_id FROM t_follow WHERE following_id = #{followingId}")
    List<Long> selectFollowerIds(@Param("followingId") Long followingId);

    /** 我关注的人也关注了谁（二度关系候选）。 */
    @Select("SELECT f2.following_id FROM t_follow f1 JOIN t_follow f2 ON f1.following_id = f2.follower_id "
            + "WHERE f1.follower_id = #{userId} AND f2.following_id <> #{userId} "
            + "GROUP BY f2.following_id ORDER BY COUNT(*) DESC LIMIT #{limit}")
    List<Long> selectSecondDegree(@Param("userId") Long userId, @Param("limit") int limit);

    /** 与我有共同关注的人数，用于推荐理由。 */
    @Select("SELECT f2.following_id AS userId, COUNT(*) AS cnt FROM t_follow f1 "
            + "JOIN t_follow f2 ON f1.following_id = f2.follower_id "
            + "WHERE f1.follower_id = #{userId} AND f2.following_id <> #{userId} "
            + "GROUP BY f2.following_id ORDER BY cnt DESC LIMIT #{limit}")
    List<MutualRow> selectMutualCounts(@Param("userId") Long userId, @Param("limit") int limit);

    /** 关注了我、我还没回关的人（回关优先）。 */
    @Select("SELECT f.follower_id FROM t_follow f WHERE f.following_id = #{userId} "
            + "AND NOT EXISTS (SELECT 1 FROM t_follow x WHERE x.follower_id = #{userId} "
            + "AND x.following_id = f.follower_id) ORDER BY f.create_time DESC LIMIT #{limit}")
    List<Long> selectFollowersNotFollowingBack(@Param("userId") Long userId, @Param("limit") int limit);

    /** 最近关注我的粉丝（主页粉丝榜兜底）。 */
    @Select("SELECT follower_id FROM t_follow WHERE following_id = #{userId} "
            + "ORDER BY create_time DESC LIMIT #{limit}")
    List<Long> selectRecentFollowers(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("<script>SELECT following_id AS userId, COUNT(*) AS cnt FROM t_follow WHERE following_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY following_id</script>")
    List<FanCountRow> selectFansCounts(@Param("ids") java.util.Collection<Long> ids);

    @lombok.Data
    class FanCountRow {
        private Long userId;
        private Long cnt;
    }

    @lombok.Data
    class MutualRow {
        private Long userId;
        private Long cnt;
    }

    @Select("<script>SELECT following_id FROM t_follow WHERE follower_id = #{followerId} AND following_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectFollowingIds(@Param("followerId") Long followerId, @Param("ids") List<Long> ids);
}
