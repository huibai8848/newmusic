package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.CopyrightInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CopyrightInfoMapper extends BaseMapper<CopyrightInfo> {

    @Select("SELECT * FROM t_copyright WHERE license_end IS NOT NULL AND license_end <= #{deadline} "
            + "AND status IN (0, 1) ORDER BY license_end ASC")
    List<CopyrightInfo> selectExpiring(@Param("deadline") LocalDate deadline);

    @Select("SELECT * FROM t_copyright WHERE license_end IS NOT NULL AND license_end < #{today} "
            + "AND status IN (0, 1) ORDER BY license_end ASC")
    List<CopyrightInfo> selectExpired(@Param("today") LocalDate today);

    @Select("SELECT COUNT(*) FROM t_copyright WHERE song_id = #{songId} AND status IN (0, 1) "
            + "AND (license_end IS NULL OR license_end >= #{today})")
    long countValidForSong(@Param("songId") Long songId, @Param("today") LocalDate today);
}
