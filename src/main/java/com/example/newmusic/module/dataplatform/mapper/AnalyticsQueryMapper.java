package com.example.newmusic.module.dataplatform.mapper;

import com.example.newmusic.module.dataplatform.model.DayValueRow;
import com.example.newmusic.module.dataplatform.model.DimValueRow;
import com.example.newmusic.module.dataplatform.model.FirstActiveRow;
import com.example.newmusic.module.dataplatform.model.FunnelRow;
import com.example.newmusic.module.dataplatform.model.RetentionDayRow;
import com.example.newmusic.module.dataplatform.model.RetentionFactRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 分析类只读查询（趋势 / 下钻 / 漏斗 / 留存）。
 *
 * <p>只查 ODS 与 DWD，不碰业务库写路径；{@code ${column}} / {@code ${prop}} 一律由
 * Service 用白名单校验后再传入。
 */
@Mapper
public interface AnalyticsQueryMapper {

    String VALID_PLAY_SQL =
            "(event_name = 'player_valid_play' OR (event_name = 'player_song_end' AND ("
                    + "JSON_UNQUOTE(JSON_EXTRACT(props, '$.is_valid_play')) IN ('true','1') OR "
                    + "CAST(JSON_UNQUOTE(JSON_EXTRACT(props, '$.play_duration')) AS SIGNED) >= #{validSeconds})))";

    // ------------------------------------------------------------------ 趋势

    @Select("SELECT dt, COUNT(DISTINCT COALESCE(user_id, CONCAT('d:', device_id))) AS v "
            + "FROM newmusic.t_track_event WHERE dt BETWEEN #{start} AND #{end} "
            + "AND (user_id IS NOT NULL OR device_id IS NOT NULL) GROUP BY dt ORDER BY dt")
    List<DayValueRow> activeSubjectByDay(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT dt, COUNT(*) AS v FROM newmusic.t_track_event "
            + "WHERE event_name = #{eventName} AND dt BETWEEN #{start} AND #{end} "
            + "GROUP BY dt ORDER BY dt")
    List<DayValueRow> eventCountByDay(@Param("eventName") String eventName,
                                      @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT dt, COUNT(DISTINCT user_id) AS v FROM newmusic.t_track_event "
            + "WHERE event_name = #{eventName} AND user_id IS NOT NULL "
            + "AND dt BETWEEN #{start} AND #{end} GROUP BY dt ORDER BY dt")
    List<DayValueRow> distinctUserByEventByDay(@Param("eventName") String eventName,
                                               @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT dt, COUNT(*) AS v FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} AND " + VALID_PLAY_SQL
            + " GROUP BY dt ORDER BY dt")
    List<DayValueRow> validPlayByDay(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                     @Param("validSeconds") int validSeconds);

    @Select("SELECT dt, SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(props, '$.${prop}')) AS SIGNED)) AS v "
            + "FROM newmusic.t_track_event WHERE event_name = #{eventName} "
            + "AND dt BETWEEN #{start} AND #{end} GROUP BY dt ORDER BY dt")
    List<DayValueRow> sumPropByDay(@Param("eventName") String eventName, @Param("prop") String prop,
                                   @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("<script>SELECT dt, COUNT(*) AS v FROM newmusic.t_track_event WHERE dt BETWEEN #{start} AND #{end} "
            + "AND event_name IN <foreach collection='eventNames' item='n' open='(' separator=',' close=')'>#{n}</foreach> "
            + "GROUP BY dt ORDER BY dt</script>")
    List<DayValueRow> eventCountInByDay(@Param("eventNames") List<String> eventNames,
                                        @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT dt, COUNT(DISTINCT user_id) AS v FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} AND user_id IS NOT NULL "
            + "AND event_name IN ('post_like_click','post_comment_submit','user_follow_click','post_publish_success') "
            + "GROUP BY dt ORDER BY dt")
    List<DayValueRow> interactionUsersByDay(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("SELECT t.dt, COUNT(*) AS v FROM ("
            + "SELECT dt, user_id, COUNT(*) AS c FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} AND user_id IS NOT NULL AND " + VALID_PLAY_SQL
            + " GROUP BY dt, user_id HAVING c >= #{threshold}) t GROUP BY t.dt ORDER BY t.dt")
    List<DayValueRow> dauPlayByDay(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                   @Param("validSeconds") int validSeconds,
                                   @Param("threshold") int threshold);

    // ------------------------------------------------------------------ 下钻

    @Select("SELECT ${column} AS dimValue, COUNT(DISTINCT COALESCE(user_id, CONCAT('d:', device_id))) AS v "
            + "FROM newmusic.t_track_event WHERE dt = #{date} "
            + "AND (user_id IS NOT NULL OR device_id IS NOT NULL) GROUP BY ${column} "
            + "ORDER BY v DESC LIMIT 50")
    List<DimValueRow> drillActiveSubject(@Param("date") LocalDate date, @Param("column") String column);

    @Select("SELECT ${column} AS dimValue, COUNT(*) AS v FROM newmusic.t_track_event "
            + "WHERE event_name = #{eventName} AND dt = #{date} GROUP BY ${column} "
            + "ORDER BY v DESC LIMIT 50")
    List<DimValueRow> drillEventCount(@Param("eventName") String eventName, @Param("date") LocalDate date,
                                      @Param("column") String column);

    @Select("SELECT ${column} AS dimValue, COUNT(*) AS v FROM newmusic.t_track_event "
            + "WHERE dt = #{date} AND " + VALID_PLAY_SQL + " GROUP BY ${column} ORDER BY v DESC LIMIT 50")
    List<DimValueRow> drillValidPlay(@Param("date") LocalDate date, @Param("column") String column,
                                     @Param("validSeconds") int validSeconds);

    @Select("SELECT ${column} AS dimValue, COUNT(DISTINCT user_id) AS v FROM newmusic.t_track_event "
            + "WHERE event_name = #{eventName} AND user_id IS NOT NULL AND dt = #{date} "
            + "GROUP BY ${column} ORDER BY v DESC LIMIT 50")
    List<DimValueRow> drillDistinctUserByEvent(@Param("eventName") String eventName,
                                               @Param("date") LocalDate date,
                                               @Param("column") String column);

    // ------------------------------------------------------------------ 漏斗

    @Select("<script>SELECT user_id AS userId, device_id AS deviceId, event_name AS eventName, "
            + "server_time AS serverTime, props, page_id AS pageId, platform, os, network, region, "
            + "is_new_user AS isNewUser "
            + "FROM newmusic.t_track_event WHERE dt BETWEEN #{start} AND #{end} AND event_name IN "
            + "<foreach collection='eventNames' item='n' open='(' separator=',' close=')'>#{n}</foreach>"
            + "<if test='dimensionColumn != null and dimensionValue != null'>"
            + " AND ${dimensionColumn} = #{dimensionValue}</if> "
            + "ORDER BY COALESCE(user_id, 0), device_id, server_time LIMIT #{limit}</script>")
    List<FunnelRow> funnelRows(@Param("start") LocalDate start, @Param("end") LocalDate end,
                               @Param("eventNames") List<String> eventNames,
                               @Param("dimensionColumn") String dimensionColumn,
                               @Param("dimensionValue") String dimensionValue,
                               @Param("limit") int limit);

    // ------------------------------------------------------------------ 留存

    @Select("<script>SELECT user_id AS userId, dt FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} AND user_id IS NOT NULL "
            + "<if test='playOnly'>AND event_name IN ('player_song_play','player_valid_play')</if> "
            + "GROUP BY user_id, dt LIMIT #{limit}</script>")
    List<RetentionDayRow> activeDays(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                     @Param("playOnly") boolean playOnly, @Param("limit") int limit);

    @Select("SELECT user_id AS userId, MIN(dt) AS firstDt FROM newmusic.t_track_event "
            + "WHERE dt <= #{end} AND user_id IS NOT NULL GROUP BY user_id")
    List<FirstActiveRow> firstActive(@Param("end") LocalDate end);

    /** 注册日期（新增留存的 cohort 基准，按 t_user 的真实注册时间而不是首次埋点）。 */
    @Select("SELECT id AS userId, DATE(create_time) AS dt FROM newmusic.t_user "
            + "WHERE deleted = 0 AND DATE(create_time) BETWEEN #{start} AND #{end}")
    List<RetentionDayRow> registeredUsers(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 有效播放过的「用户 × 自然日」，播放留存用。 */
    @Select("<script>SELECT user_id AS userId, dt FROM newmusic.t_track_event "
            + "WHERE dt BETWEEN #{start} AND #{end} AND user_id IS NOT NULL AND " + VALID_PLAY_SQL
            + " GROUP BY user_id, dt LIMIT #{limit}</script>")
    List<RetentionDayRow> validPlayDays(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                       @Param("validSeconds") int validSeconds,
                                       @Param("limit") int limit);

    @Select("<script>SELECT user_id AS userId, dt, "
            + "SUM(event_name = 'player_song_play') AS plays, "
            + "SUM(event_name IN ('post_like_click','post_comment_submit','user_follow_click','post_publish_success')) AS social, "
            + "SUM(event_name IN ('room_enter_success','room_song_order','room_danmaku_send')) AS room, "
            + "SUM(event_name LIKE 'ai_%') AS ai "
            + "FROM newmusic.t_track_event WHERE dt BETWEEN #{start} AND #{end} AND user_id IS NOT NULL "
            + "GROUP BY user_id, dt LIMIT #{limit}</script>")
    List<RetentionFactRow> retentionFacts(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                          @Param("limit") int limit);
}
