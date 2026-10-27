package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.FansRank;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface FansRankMapper extends BaseMapper<FansRank> {

    @Select("SELECT * FROM t_fans_rank WHERE user_id = #{userId} AND rank_type = #{type} "
            + "AND period = #{period} ORDER BY stat_date DESC LIMIT 1")
    FansRank selectLatest(@Param("userId") Long userId, @Param("type") int type,
                          @Param("period") String period);

    @Insert("INSERT INTO t_fans_rank (id, user_id, rank_type, period, stat_date, rank_data, create_time) "
            + "VALUES (#{id}, #{userId}, #{type}, #{period}, #{date}, #{data}, NOW(3)) "
            + "ON DUPLICATE KEY UPDATE rank_data = #{data}, create_time = NOW(3)")
    int upsert(@Param("id") Long id, @Param("userId") Long userId, @Param("type") int type,
               @Param("period") String period, @Param("date") LocalDate date, @Param("data") String data);
}
