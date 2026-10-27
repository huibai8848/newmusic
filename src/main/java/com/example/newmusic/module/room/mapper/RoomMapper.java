package com.example.newmusic.module.room.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.room.entity.Room;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RoomMapper extends BaseMapper<Room> {

    /**
     * 在线人数镜像更新。Redis 是实时真相，这里只在进出房时同步一次，
     * 列表页的大盘数字仍以 Redis 为准。
     */
    @Update("UPDATE t_room SET online_count = #{online}, last_active_time = #{now}, "
            + "update_time = NOW(3) WHERE id = #{roomId} AND deleted = 0")
    int updateOnline(@Param("roomId") Long roomId, @Param("online") int online,
                     @Param("now") LocalDateTime now);

    /** 停留时长累加（离开房间时调用，BR-14-022）。 */
    @Update("UPDATE t_room SET total_duration = total_duration + #{seconds}, "
            + "update_time = NOW(3) WHERE id = #{roomId} AND deleted = 0")
    int addDuration(@Param("roomId") Long roomId, @Param("seconds") long seconds);

    /** 互动计数只落一次库（热度任务读取后清零），避免每条聊天都写主表。 */
    @Update("UPDATE t_room SET hot_score = #{score}, update_time = NOW(3) "
            + "WHERE id = #{roomId} AND deleted = 0")
    int updateHotScore(@Param("roomId") Long roomId, @Param("score") int score);

    /** 控播切歌后同步房间当前歌曲（列表页展示用；权威播放态仍在 Redis）。 */
    @Update("UPDATE t_room SET current_song_id = #{songId}, last_active_time = NOW(3), "
            + "update_time = NOW(3) WHERE id = #{roomId} AND deleted = 0")
    int updateCurrentSong(@Param("roomId") Long roomId, @Param("songId") Long songId);

    /** 空房 / 关闭状态下仅刷新活跃时间，避免把 online_count 写回非 0。 */
    @Update("UPDATE t_room SET last_active_time = #{now}, update_time = NOW(3) "
            + "WHERE id = #{roomId} AND deleted = 0")
    int touchActive(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);
}
