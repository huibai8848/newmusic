package com.example.newmusic.module.recupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.recupgrade.entity.RecEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecEventMapper extends BaseMapper<RecEvent> {

    /** 实验分组维度的事件计数。一次 SQL 取回，避免 N 组 N 次查询。 */
    @Select("SELECT experiment_key AS experimentKey, group_name AS groupName, event_type AS eventType, "
            + "COUNT(*) AS cnt, COUNT(DISTINCT user_id) AS userCnt "
            + "FROM t_rec_event WHERE experiment_key = #{experimentKey} "
            + "AND create_time >= #{since} AND create_time <= #{until} "
            + "AND (#{userType} = '' OR user_type = #{userType}) "
            + "AND (#{deviceType} = '' OR device_type = #{deviceType}) "
            + "AND (#{hour} < 0 OR hour_of_day = #{hour}) "
            + "GROUP BY experiment_key, group_name, event_type")
    List<EventCountRow> selectExperimentCounts(@Param("experimentKey") String experimentKey,
                                               @Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until,
                                               @Param("userType") String userType,
                                               @Param("deviceType") String deviceType,
                                               @Param("hour") int hour);

    /** 实验组平均有效播放时长（秒）（FR-24-009 的连续型指标）。 */
    @Select("SELECT group_name AS groupName, AVG(play_duration) AS avgValue, COUNT(*) AS cnt "
            + "FROM t_rec_event WHERE experiment_key = #{experimentKey} AND event_type = 'VALID_PLAY' "
            + "AND create_time >= #{since} AND create_time <= #{until} GROUP BY group_name")
    List<AvgRow> selectAvgValidPlay(@Param("experimentKey") String experimentKey,
                                    @Param("since") LocalDateTime since,
                                    @Param("until") LocalDateTime until);

    @lombok.Data
    class AvgRow {
        private String groupName;
        private Double avgValue;
        private Long cnt;
    }

    /** 日报聚合：按场景/实验/组/事件类型一次取回一天的计数与平均播放时长。 */
    @Select("SELECT scene, experiment_key AS experimentKey, group_name AS groupName, "
            + "event_type AS eventType, COUNT(*) AS cnt, COUNT(DISTINCT user_id) AS userCnt, "
            + "AVG(CASE WHEN event_type = 'VALID_PLAY' THEN play_duration END) AS avgValue "
            + "FROM t_rec_event WHERE create_time >= #{since} AND create_time < #{until} "
            + "GROUP BY scene, experiment_key, group_name, event_type")
    List<EventCountRow> selectDailyCounts(@Param("since") LocalDateTime since,
                                          @Param("until") LocalDateTime until);

    /** 新歌扶持效果：曝光与完成次数（FR-24-006 的停止条件）。 */
    @Select("SELECT song_id AS songId, "
            + "SUM(CASE WHEN event_type IN ('IMPRESSION','NEW_SONG_EXPOSURE') THEN 1 ELSE 0 END) AS exposure, "
            + "SUM(CASE WHEN event_type = 'COMPLETE' THEN 1 ELSE 0 END) AS completeCount "
            + "FROM t_rec_event WHERE song_id IS NOT NULL AND create_time >= #{since} "
            + "GROUP BY song_id")
    List<NewSongStatRow> selectNewSongStats(@org.apache.ibatis.annotations.Param("since") LocalDateTime since);

    @lombok.Data
    class NewSongStatRow {
        private Long songId;
        private Long exposure;
        private Long completeCount;
    }

    @lombok.Data
    class EventCountRow {
        private String scene;
        private String experimentKey;
        private String groupName;
        private String eventType;
        private Long cnt;
        private Long userCnt;
        private Double avgValue;
    }
}
