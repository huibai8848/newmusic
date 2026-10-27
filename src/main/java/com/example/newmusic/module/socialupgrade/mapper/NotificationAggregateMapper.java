package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.NotificationAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationAggregateMapper extends BaseMapper<NotificationAggregate> {

    @Select("SELECT * FROM t_notification_aggregate WHERE user_id = #{userId} AND aggregate_key = #{key}")
    NotificationAggregate selectByKey(@Param("userId") Long userId, @Param("key") String key);

    @Update("UPDATE t_notification_aggregate SET participant_ids = #{participants}, count = #{count}, "
            + "update_time = NOW(3) WHERE id = #{id}")
    int updateAggregate(@Param("id") Long id, @Param("participants") String participants,
                        @Param("count") int count);
}
