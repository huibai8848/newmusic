package com.example.newmusic.module.dataplatform.mapper;

import com.example.newmusic.module.dataplatform.model.DimValueRow;
import com.example.newmusic.module.dataplatform.model.IdNameRow;
import com.example.newmusic.module.dataplatform.model.TopItemRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 数据质量、对账与报表所需的计数查询。 */
@Mapper
public interface GovernanceQueryMapper {

    // ------------------------------------------------------------------ 质量

    @Select("SELECT COUNT(*) FROM newmusic.t_track_event WHERE dt = #{date}")
    long countEvents(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT event_id) FROM newmusic.t_track_event WHERE dt = #{date}")
    long countDistinctEventIds(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_track_event WHERE dt = #{date} "
            + "AND user_id IS NULL AND device_id IS NULL")
    long countNullSubjects(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT user_id) FROM newmusic.t_track_event "
            + "WHERE dt = #{date} AND user_id IS NOT NULL")
    long countTrackUsers(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_track_event "
            + "WHERE dt = #{date} AND event_name = #{eventName}")
    long countEventByName(@Param("date") LocalDate date, @Param("eventName") String eventName);

    @Select("SELECT COUNT(*) FROM newmusic.t_dwd_play_detail "
            + "WHERE dt = #{date} AND completion_rate >= #{ratio}")
    long countComplete(@Param("date") LocalDate date, @Param("ratio") BigDecimal ratio);

    @Select("SELECT COUNT(*) FROM newmusic.t_data_report WHERE report_date = #{date}")
    long countReports(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_data_quality_record WHERE check_date = #{date}")
    long countQualityRules(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_data_quality_record WHERE check_date = #{date} AND passed = 1")
    long countQualityPassed(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_track_dirty WHERE DATE(create_time) = #{date}")
    long countDirty(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    long countDwd(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) - COUNT(DISTINCT event_id) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    long duplicateDwdEventIds(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(MIN(play_duration), 0) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    Integer minPlayDuration(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(MAX(play_duration), 0) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    Integer maxPlayDuration(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(MIN(completion_rate), 0) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    BigDecimal minCompletionRate(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(MAX(completion_rate), 0) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    BigDecimal maxCompletionRate(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_dws_user_daily WHERE dt = #{date}")
    long countDwsUser(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_dws_song_daily WHERE dt = #{date}")
    long countDwsSong(@Param("date") LocalDate date);

    // ------------------------------------------------------------------ 对账 / 指标

    @Select("SELECT COUNT(*) FROM newmusic.t_user WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countUsersCreated(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_user "
            + "WHERE deleted = 0 AND DATE(create_time) <= #{date} AND (ban_until IS NULL OR ban_until > NOW())")
    long countUsersTotal(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_play_history WHERE deleted = 0 AND DATE(play_time) = #{date}")
    long countPlayHistory(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_user_post WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countPosts(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_like WHERE DATE(create_time) = #{date}")
    long countLikes(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_comment WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countComments(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_collect WHERE DATE(create_time) = #{date}")
    long countCollects(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(SUM(cost_cent), 0) FROM newmusic.t_ai_call_log WHERE DATE(create_time) = #{date}")
    long sumAiCostCent(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_room_play_log WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countRoomPlayLogs(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(SUM(${field}), 0) FROM newmusic.t_dws_user_daily WHERE dt = #{date}")
    long sumDwsUserField(@Param("field") String field, @Param("date") LocalDate date);

    @Select("SELECT COALESCE(SUM(${field}), 0) FROM newmusic.t_dws_song_daily WHERE dt = #{date}")
    long sumDwsSongField(@Param("field") String field, @Param("date") LocalDate date);

    /** 前 7 日平均行数，用于波动检测（BR-26-105）。 */
    @Select("SELECT COALESCE(AVG(c), 0) FROM (SELECT COUNT(*) AS c FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} GROUP BY dt) t")
    double avgEventCount(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT MAX(server_time) FROM newmusic.t_track_event WHERE dt = #{date}")
    java.time.LocalDateTime maxServerTime(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT user_id) FROM newmusic.t_play_history "
            + "WHERE deleted = 0 AND DATE(play_time) = #{date}")
    long countPlayUsers(@Param("date") LocalDate date);

    @Select("SELECT COALESCE(metric_value, 0) FROM newmusic.t_ads_daily_metrics "
            + "WHERE stat_date = #{date} AND metric_id = #{metricId} "
            + "AND dimension = 'ALL' AND dimension_value = '' LIMIT 1")
    BigDecimal adsMetric(@Param("date") LocalDate date, @Param("metricId") String metricId);

    @Select("SELECT COUNT(*) FROM newmusic.t_ads_daily_metrics WHERE stat_date = #{date}")
    long countAds(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_song WHERE deleted = 0 AND status = 1")
    long countSongsTotal();

    @Select("SELECT COUNT(DISTINCT song_id) FROM newmusic.t_dwd_play_detail WHERE dt = #{date}")
    long countPlayedSongs(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT COALESCE(user_id, CONCAT('d:', device_id))) FROM newmusic.t_track_event "
            + "WHERE dt = #{date} AND (user_id IS NOT NULL OR device_id IS NOT NULL)")
    long countDau(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_dws_user_daily WHERE dt = #{date} AND valid_play_cnt >= #{threshold}")
    long countDauPlay(@Param("date") LocalDate date, @Param("threshold") int threshold);

    @Select("SELECT COUNT(DISTINCT user_id) FROM newmusic.t_track_event WHERE dt = #{date} "
            + "AND event_name = 'room_enter_success' AND user_id IS NOT NULL")
    long countRoomUsers(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT user_id) FROM newmusic.t_track_event WHERE dt = #{date} "
            + "AND event_name IN ('post_like_click','post_comment_submit','user_follow_click','post_publish_success') "
            + "AND user_id IS NOT NULL")
    long countInteractionUsers(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_playlist WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countPlaylistsCreated(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT user_id) FROM newmusic.t_track_event WHERE dt = #{date} "
            + "AND user_id IS NOT NULL AND HOUR(server_time) >= 23")
    long countLateHourUsers(@Param("date") LocalDate date);

    @Select("SELECT COUNT(DISTINCT e.user_id) FROM newmusic.t_dwd_play_detail e "
            + "JOIN newmusic.t_user u ON u.id = e.user_id "
            + "WHERE u.deleted = 0 AND DATE(u.create_time) = #{date} AND e.is_valid = 1 "
            + "AND e.dt = DATE_ADD(#{date}, INTERVAL #{days} DAY)")
    long countRetainedNewUsers(@Param("date") LocalDate date, @Param("days") int days);

    // ------------------------------------------------------------------ 报表分节

    @Select("SELECT COUNT(*) FROM newmusic.t_song WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countSongsPublished(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_song WHERE deleted = 0 AND status = 0 AND DATE(update_time) = #{date}")
    long countSongsOffline(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_room WHERE deleted = 0 AND DATE(create_time) = #{date}")
    long countRoomsCreated(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_room WHERE deleted = 0 AND DATE(last_active_time) = #{date}")
    long countActiveRooms(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_room_message WHERE DATE(create_time) = #{date}")
    long countRoomMessages(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_private_message WHERE DATE(create_time) = #{date}")
    long countPrivateMessages(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_report WHERE DATE(create_time) = #{date}")
    long countContentReports(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_audit_record WHERE DATE(create_time) = #{date}")
    long countAuditRecords(@Param("date") LocalDate date);

    @Select("SELECT COUNT(*) FROM newmusic.t_ai_call_log WHERE DATE(create_time) = #{date}")
    long countAiCalls(@Param("date") LocalDate date);

    @Select("SELECT keyword AS dimValue, COUNT(*) AS v FROM newmusic.t_search_history "
            + "WHERE DATE(create_time) = #{date} AND keyword IS NOT NULL "
            + "GROUP BY keyword ORDER BY v DESC LIMIT #{limit}")
    List<DimValueRow> topSearchKeywords(@Param("date") LocalDate date, @Param("limit") int limit);

    @Select("SELECT JSON_UNQUOTE(JSON_EXTRACT(props, '$.channel')) AS dimValue, COUNT(*) AS v "
            + "FROM newmusic.t_track_event WHERE event_name = 'user_register_success' AND dt = #{date} "
            + "GROUP BY dimValue ORDER BY v DESC")
    List<DimValueRow> registerChannelDist(@Param("date") LocalDate date);

    @Select("<script>SELECT id, name FROM newmusic.t_song WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<IdNameRow> songNames(@Param("ids") List<Long> ids);

    @Select("<script>SELECT id, name FROM newmusic.t_room WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<IdNameRow> roomNames(@Param("ids") List<Long> ids);

    @Select("SELECT song_id AS id, MAX(song_name) AS name, COUNT(*) AS cnt FROM newmusic.t_play_history "
            + "WHERE deleted = 0 AND DATE(play_time) = #{date} GROUP BY song_id "
            + "ORDER BY cnt DESC LIMIT #{limit}")
    List<TopItemRow> topSongsFromHistory(@Param("date") LocalDate date, @Param("limit") int limit);

    @Select("SELECT m.room_id AS id, MAX(r.name) AS name, COUNT(*) AS cnt FROM newmusic.t_room_message m "
            + "LEFT JOIN newmusic.t_room r ON r.id = m.room_id "
            + "WHERE DATE(m.create_time) = #{date} GROUP BY m.room_id ORDER BY cnt DESC LIMIT #{limit}")
    List<TopItemRow> topRoomsFromMessages(@Param("date") LocalDate date, @Param("limit") int limit);
}
