package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    @Select("SELECT * FROM t_user_profile WHERE user_id = #{userId}")
    UserProfile selectByUserId(@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO t_user_profile (user_id, gender, follow_count, fans_count, post_count, "
            + "like_received_count, total_play_duration, listen_days, cert_type, create_time, update_time) "
            + "VALUES (#{userId}, 0, 0, 0, 0, 0, 0, 0, 0, NOW(3), NOW(3))")
    int insertDefault(@Param("userId") Long userId);

    @Update("UPDATE t_user_profile SET post_count = GREATEST(0, post_count + #{delta}) WHERE user_id = #{userId}")
    int incrPostCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE t_user_profile SET like_received_count = GREATEST(0, like_received_count + #{delta}) "
            + "WHERE user_id = #{userId}")
    int incrLikeReceived(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("UPDATE t_user_profile SET fans_count = #{count} WHERE user_id = #{userId}")
    int setFansCount(@Param("userId") Long userId, @Param("count") int count);

    @Update("UPDATE t_user_profile SET follow_count = #{count} WHERE user_id = #{userId}")
    int setFollowCount(@Param("userId") Long userId, @Param("count") int count);

    @Select("SELECT user_id FROM t_user_profile WHERE user_code = #{code}")
    Long selectUserIdByCode(@Param("code") String code);
}
