package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.UserRelationScore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserRelationScoreMapper extends BaseMapper<UserRelationScore> {

    @Select("SELECT * FROM t_user_relation_score WHERE user_id = #{userId} AND target_user_id = #{targetId}")
    UserRelationScore selectPair(@Param("userId") Long userId, @Param("targetId") Long targetId);

    @Insert("INSERT INTO t_user_relation_score (id, user_id, target_user_id, score, level, "
            + "last_interact_time, create_time, update_time) VALUES "
            + "(#{id}, #{userId}, #{targetId}, #{delta}, 0, #{now}, NOW(3), NOW(3)) "
            + "ON DUPLICATE KEY UPDATE score = score + #{delta}, last_interact_time = #{now}, update_time = NOW(3)")
    int upsertIncrement(@Param("id") Long id, @Param("userId") Long userId, @Param("targetId") Long targetId,
                        @Param("delta") int delta, @Param("now") LocalDateTime now);

    /**
     * 记录互动：先把既有分数按最后互动时间衰减到当前时刻，再累加本次权重。
     *
     * <p>相比“读取时统一衰减”，这样每次写入都把历史分值结算到 now，
     * 不会因为一次新互动把旧分值重新“激活”。
     */
    @Insert("INSERT INTO t_user_relation_score (id, user_id, target_user_id, score, level, "
            + "last_interact_time, create_time, update_time) VALUES "
            + "(#{id}, #{userId}, #{targetId}, #{delta}, 0, #{now}, NOW(3), NOW(3)) "
            + "ON DUPLICATE KEY UPDATE "
            + "score = ROUND(score * EXP(-#{lambda} * "
            + "(TIMESTAMPDIFF(SECOND, COALESCE(last_interact_time, #{now}), #{now}) / 86400.0))) "
            + "+ #{delta}, last_interact_time = #{now}, update_time = NOW(3)")
    int upsertDecayed(@Param("id") Long id, @Param("userId") Long userId, @Param("targetId") Long targetId,
                      @Param("delta") int delta, @Param("lambda") double lambda,
                      @Param("now") LocalDateTime now);

    @Update("UPDATE t_user_relation_score SET level = #{level} WHERE user_id = #{userId} AND target_user_id = #{targetId}")
    int updateLevel(@Param("userId") Long userId, @Param("targetId") Long targetId, @Param("level") int level);

    @Select("SELECT * FROM t_user_relation_score WHERE user_id = #{userId} AND target_user_id = #{targetId}")
    UserRelationScore selectLevelPair(@Param("userId") Long userId, @Param("targetId") Long targetId);

    @Select("SELECT * FROM t_user_relation_score WHERE target_user_id = #{targetId} "
            + "ORDER BY score DESC LIMIT #{limit}")
    List<UserRelationScore> selectTopFans(@Param("targetId") Long targetId, @Param("limit") int limit);
}
