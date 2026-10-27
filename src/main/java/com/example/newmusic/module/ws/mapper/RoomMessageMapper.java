package com.example.newmusic.module.ws.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ws.entity.RoomMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoomMessageMapper extends BaseMapper<RoomMessage> {

    /**
     * 断线补偿：按 ID 游标向后取。
     *
     * <p>用 ID 而不是 create_time：雪花 ID 单调递增，同一毫秒内的多条消息也有稳定顺序；
     * {@code create_time} 只有毫秒精度，游标会重复或漏掉同一毫秒的消息。
     */
    @Select("SELECT * FROM t_room_message "
            + "WHERE room_id = #{roomId} AND id > #{sinceId} AND status = 1 "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<RoomMessage> listSince(@Param("roomId") Long roomId,
                                @Param("sinceId") long sinceId,
                                @Param("limit") int limit);
}
