package com.example.newmusic.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ai.entity.AiCallLog;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用日志与成本统计。
 *
 * <p>统计一律在 SQL 里聚合，不把日志行捞进 JVM：日志表是增长最快的表，
 * 「查出来再 for 循环 SUM」在数据量上来后会直接把后台拖死。
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

    @Select("SELECT scene, COUNT(*) AS calls, "
            + "COALESCE(SUM(from_cache), 0) AS cacheHits, "
            + "COALESCE(SUM(success), 0) AS successCount, "
            + "COALESCE(SUM(degraded), 0) AS degradedCount, "
            + "COALESCE(SUM(cost_cent), 0) AS costCent, "
            + "COALESCE(ROUND(AVG(latency_ms)), 0) AS avgLatencyMs "
            + "FROM t_ai_call_log WHERE create_time >= #{start} AND create_time < #{end} "
            + "GROUP BY scene ORDER BY costCent DESC, calls DESC")
    List<SceneStat> selectSceneStats(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    @Select("SELECT DATE(create_time) AS day, COUNT(*) AS calls, "
            + "COALESCE(SUM(from_cache), 0) AS cacheHits, "
            + "COALESCE(SUM(cost_cent), 0) AS costCent "
            + "FROM t_ai_call_log WHERE create_time >= #{start} AND create_time < #{end} "
            + "GROUP BY DATE(create_time) ORDER BY day")
    List<DailyStat> selectDailyStats(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM t_ai_call_log WHERE create_time >= #{start} AND create_time < #{end}")
    long countBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COALESCE(SUM(cost_cent), 0) FROM t_ai_call_log "
            + "WHERE create_time >= #{start} AND create_time < #{end}")
    long sumCostBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Data
    class SceneStat {
        private String scene;
        private Long calls;
        private Long cacheHits;
        private Long successCount;
        private Long degradedCount;
        private Long costCent;
        private Long avgLatencyMs;
    }

    @Data
    class DailyStat {
        private LocalDate day;
        private Long calls;
        private Long cacheHits;
        private Long costCent;
    }
}
