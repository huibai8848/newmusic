package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.Blacklist;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface BlacklistMapper extends BaseMapper<Blacklist> {

    @Insert("INSERT IGNORE INTO t_blacklist (id, user_id, blocked_user_id, create_time) "
            + "VALUES (#{id}, #{userId}, #{blockedUserId}, #{createTime})")
    int insertIgnore(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("blockedUserId") Long blockedUserId,
                     @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_blacklist WHERE user_id = #{userId} AND blocked_user_id = #{blockedUserId}")
    int deleteByPair(@Param("userId") Long userId, @Param("blockedUserId") Long blockedUserId);

    @Select("SELECT COUNT(*) FROM t_blacklist WHERE user_id = #{userId}")
    int countByUser(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM t_blacklist WHERE user_id = #{userId} AND blocked_user_id = #{blockedUserId}")
    int exists(@Param("userId") Long userId, @Param("blockedUserId") Long blockedUserId);

    @Select("SELECT blocked_user_id FROM t_blacklist WHERE user_id = #{userId}")
    java.util.List<Long> selectBlockedIds(@Param("userId") Long userId);

    @Select("SELECT user_id FROM t_blacklist WHERE blocked_user_id = #{userId}")
    java.util.List<Long> selectBlockerIds(@Param("userId") Long userId);
}
