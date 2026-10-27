package com.example.newmusic.module.room.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.room.entity.RoomMic;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoomMicMapper extends BaseMapper<RoomMic> {

    /**
     * 原子占麦：只有“仍然空闲”的行会更新成功。
     *
     * <p>外层还有 {@code ml:lock:room:mic:{roomId}} 分布式锁；这个 CAS 是第二道保险，
     * 也让单测在没有 Redis 时仍能验证“同一麦位不会被两个人占用”。
     *
     * @return 1 表示抢占成功，0 表示已被别人占用/锁定
     */
    @Update("UPDATE t_room_mic SET user_id = #{userId}, status = 1, update_time = NOW(3), "
            + "version = version + 1 WHERE id = #{micId} AND status = 0 AND deleted = 0")
    int occupy(@Param("micId") Long micId, @Param("userId") Long userId);

    /** 释放某人在该房间的所有麦位。 */
    @Update("UPDATE t_room_mic SET user_id = NULL, status = 0, update_time = NOW(3), "
            + "version = version + 1 WHERE room_id = #{roomId} AND user_id = #{userId} "
            + "AND deleted = 0")
    int releaseByUser(@Param("roomId") Long roomId, @Param("userId") Long userId);

    /** 释放单个麦位（下麦）。1 号麦同样置空，但保留“房主专属位”的语义由业务层保证。 */
    @Update("UPDATE t_room_mic SET user_id = NULL, status = 0, update_time = NOW(3), "
            + "version = version + 1 WHERE room_id = #{roomId} AND mic_pos = #{micPos} "
            + "AND deleted = 0")
    int releasePos(@Param("roomId") Long roomId, @Param("micPos") int micPos);

    @Update("UPDATE t_room_mic SET status = #{status}, update_time = NOW(3), version = version + 1 "
            + "WHERE room_id = #{roomId} AND mic_pos = #{micPos} AND status <> 1 AND deleted = 0")
    int updateLock(@Param("roomId") Long roomId, @Param("micPos") int micPos,
                   @Param("status") int status);

    /**
     * 物理删除超出新麦位数的行。
     *
     * <p>必须物理删除：{@code uk_room_mic_pos(room_id, mic_pos)} 不含 {@code deleted}，
     * 逻辑删除的旧行会让“先减到 4 再调回 6”时的 INSERT 撞唯一键。
     */
    @Delete("DELETE FROM t_room_mic WHERE room_id = #{roomId} AND mic_pos > #{micPos}")
    int deleteAbove(@Param("roomId") Long roomId, @Param("micPos") int micPos);
}
