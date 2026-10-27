package com.example.newmusic.module.room.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.room.entity.RoomMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RoomMemberMapper extends BaseMapper<RoomMember> {

    /**
     * 累计停留时长并清空本次在线起点。
     *
     * <p>用 `last_enter_time IS NOT NULL` 做幂等：断线回调、HTTP leave、踢人三条路径
     * 可能都触发一次离开，只有第一次会加时长。
     */
    @Update("UPDATE t_room_member SET total_duration = total_duration + "
            + "TIMESTAMPDIFF(SECOND, last_enter_time, #{now}), leave_time = #{now}, "
            + "last_enter_time = NULL, update_time = NOW(3) "
            + "WHERE room_id = #{roomId} AND user_id = #{userId} AND last_enter_time IS NOT NULL "
            + "AND deleted = 0")
    int accumulateDuration(@Param("roomId") Long roomId, @Param("userId") Long userId,
                           @Param("now") LocalDateTime now);

    @Update("UPDATE t_room_member SET room_role = #{role}, update_time = NOW(3) "
            + "WHERE room_id = #{roomId} AND user_id = #{userId} AND deleted = 0")
    int updateRole(@Param("roomId") Long roomId, @Param("userId") Long userId,
                   @Param("role") int role);

    @Update("UPDATE t_room_member SET mute_status = #{status}, mute_until = #{until}, "
            + "update_time = NOW(3) WHERE room_id = #{roomId} AND user_id = #{userId} AND deleted = 0")
    int updateMute(@Param("roomId") Long roomId, @Param("userId") Long userId,
                   @Param("status") int status, @Param("until") LocalDateTime until);
}
