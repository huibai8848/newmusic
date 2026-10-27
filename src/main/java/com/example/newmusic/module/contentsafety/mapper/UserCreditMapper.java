package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.UserCredit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserCreditMapper extends BaseMapper<UserCredit> {

    @Insert("INSERT INTO t_user_credit (user_id, score, violation_count_90d, update_time) "
            + "VALUES (#{userId}, 100, 0, NOW(3)) ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensure(@Param("userId") Long userId);

    @Select("SELECT * FROM t_user_credit WHERE user_id = #{userId} FOR UPDATE")
    UserCredit selectForUpdate(@Param("userId") Long userId);
}
