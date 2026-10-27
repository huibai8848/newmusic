package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.UserRestriction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserRestrictionMapper extends BaseMapper<UserRestriction> {

    @Select("SELECT * FROM t_user_restriction WHERE user_id = #{userId} AND status = 1 "
            + "AND (end_time IS NULL OR end_time > NOW(3)) ORDER BY create_time DESC")
    List<UserRestriction> selectActive(@Param("userId") Long userId);

    @Select("SELECT * FROM t_user_restriction WHERE status = 1 "
            + "AND end_time IS NOT NULL AND end_time <= #{now} ORDER BY id ASC LIMIT 500")
    List<UserRestriction> selectExpired(@Param("now") LocalDateTime now);

    @Update("UPDATE t_user_restriction SET status = 2, update_time = NOW(3) "
            + "WHERE status = 1 AND end_time IS NOT NULL AND end_time <= #{now}")
    int expireDue(@Param("now") LocalDateTime now);

    @Update("UPDATE t_user_restriction SET status = 0, update_time = NOW(3), remark = CONCAT(COALESCE(remark,''), "
            + "' [申诉撤销]') WHERE id = #{id} AND status = 1")
    int release(@Param("id") Long id);

    /** 管理端主动解除：保留原因，不写死「申诉撤销」。 */
    @Update("UPDATE t_user_restriction SET status = 0, update_time = NOW(3), "
            + "remark = CONCAT(COALESCE(remark,''), #{suffix}) WHERE id = #{id} AND status = 1")
    int releaseManual(@Param("id") Long id, @Param("suffix") String suffix);
}
