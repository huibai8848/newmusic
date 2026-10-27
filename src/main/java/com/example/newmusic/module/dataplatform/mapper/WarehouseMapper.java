package com.example.newmusic.module.dataplatform.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 数仓加工 SQL（ODS → DWD → DWS → ADS）。
 *
 * <p>幂等策略（BR-26-093）：DWD 用 {@code INSERT IGNORE}（event_id 唯一键），
 * DWS/ADS 先删当日分区再整段重算——重跑结果与首次一致，晚到数据也能被修正。
 */
@Mapper
public interface WarehouseMapper {

    @Insert("INSERT IGNORE INTO newmusic.t_dwd_play_detail "
            + "(id, event_id, dt, user_id, device_id, song_id, artist_id, genre, language, "
            + " play_duration, song_duration, completion_rate, is_valid, from_source, position, "
            + " recommend_trace_id, quality, server_time) "
            + "SELECT e.id, e.event_id, e.dt, e.user_id, e.device_id, "
            + " CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.song_id')) AS UNSIGNED), "
            + " s.artist_id, s.genre, s.language, "
            + " COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.play_duration')) AS SIGNED), 0), "
            + " COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.song_duration')) AS SIGNED), 0), "
            + " LEAST(1.0000, GREATEST(0.0000, COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.completion_rate')) AS DECIMAL(6,4)), 0))), "
            + " CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.is_valid_play')) IN ('true','1') "
            + "        OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.play_duration')) AS SIGNED), 0) >= #{validSeconds} "
            + "        OR COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.completion_rate')) AS DECIMAL(6,4)), 0) >= #{validRatio} "
            + "      THEN 1 ELSE 0 END, "
            + " NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.from_source')), 'null'), "
            + " CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.position')) AS SIGNED), "
            + " NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.recommend_trace_id')), 'null'), "
            + " NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.quality')), 'null'), "
            + " e.server_time "
            + "FROM newmusic.t_track_event e "
            + "LEFT JOIN newmusic.t_song s ON s.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(e.props, '$.song_id')) AS UNSIGNED) "
            + "WHERE e.dt = #{date} AND e.event_name = 'player_song_end' "
            + "  AND JSON_EXTRACT(e.props, '$.song_id') IS NOT NULL")
    int insertDwdFromOds(@Param("date") LocalDate date,
                         @Param("validSeconds") int validSeconds,
                         @Param("validRatio") BigDecimal validRatio);

    @Delete("DELETE FROM newmusic.t_dws_user_daily WHERE dt = #{date}")
    int deleteDwsUser(@Param("date") LocalDate date);

    @Insert("INSERT INTO newmusic.t_dws_user_daily "
            + "(id, dt, user_id, play_cnt, valid_play_cnt, play_duration, distinct_song_cnt, "
            + " like_cnt, comment_cnt, follow_cnt, post_cnt, room_cnt, search_cnt, "
            + " recommend_click_cnt, recommend_exposure_cnt, session_cnt) "
            + "SELECT MIN(d.id), d.dt, d.user_id, COUNT(*), SUM(d.is_valid), SUM(d.play_duration), "
            + " COUNT(DISTINCT d.song_id), "
            + " MAX(COALESCE(x.like_cnt, 0)), MAX(COALESCE(x.comment_cnt, 0)), "
            + " MAX(COALESCE(x.follow_cnt, 0)), MAX(COALESCE(x.post_cnt, 0)), "
            + " MAX(COALESCE(x.room_cnt, 0)), MAX(COALESCE(x.search_cnt, 0)), "
            + " MAX(COALESCE(x.recommend_click_cnt, 0)), MAX(COALESCE(x.recommend_exposure_cnt, 0)), "
            + " MAX(COALESCE(x.session_cnt, 0)) "
            + "FROM newmusic.t_dwd_play_detail d "
            + "LEFT JOIN (SELECT user_id, "
            + "  SUM(event_name = 'post_like_click') AS like_cnt, "
            + "  SUM(event_name = 'post_comment_submit') AS comment_cnt, "
            + "  SUM(event_name = 'user_follow_click') AS follow_cnt, "
            + "  SUM(event_name = 'post_publish_success') AS post_cnt, "
            + "  SUM(event_name = 'room_enter_success') AS room_cnt, "
            + "  SUM(event_name = 'search_submit') AS search_cnt, "
            + "  SUM(event_name = 'recommend_click') AS recommend_click_cnt, "
            + "  SUM(event_name = 'recommend_exposure') AS recommend_exposure_cnt, "
            + "  COUNT(DISTINCT session_id) AS session_cnt "
            + "  FROM newmusic.t_track_event WHERE dt = #{date} AND user_id IS NOT NULL "
            + "  GROUP BY user_id) x ON x.user_id = d.user_id "
            + "WHERE d.dt = #{date} AND d.user_id IS NOT NULL "
            + "GROUP BY d.dt, d.user_id")
    int insertDwsUser(@Param("date") LocalDate date);

    @Delete("DELETE FROM newmusic.t_dws_song_daily WHERE dt = #{date}")
    int deleteDwsSong(@Param("date") LocalDate date);

    @Insert("INSERT INTO newmusic.t_dws_song_daily "
            + "(id, dt, song_id, artist_id, play_cnt, valid_play_cnt, play_duration, distinct_user_cnt, "
            + " complete_cnt, skip_cnt, like_cnt, collect_cnt, comment_cnt, share_cnt, "
            + " recommend_exposure_cnt, recommend_click_cnt) "
            + "SELECT MIN(d.id), d.dt, d.song_id, MAX(d.artist_id), COUNT(*), SUM(d.is_valid), "
            + " SUM(d.play_duration), COUNT(DISTINCT COALESCE(d.user_id, CONCAT('d:', d.device_id))), "
            + " SUM(d.completion_rate >= 0.9), SUM(d.completion_rate < 0.3), "
            + " MAX(COALESCE(x.like_cnt, 0)), MAX(COALESCE(x.collect_cnt, 0)), "
            + " MAX(COALESCE(x.comment_cnt, 0)), MAX(COALESCE(x.share_cnt, 0)), "
            + " MAX(COALESCE(x.exposure_cnt, 0)), MAX(COALESCE(x.click_cnt, 0)) "
            + "FROM newmusic.t_dwd_play_detail d "
            + "LEFT JOIN (SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(props, '$.song_id')) AS UNSIGNED) AS song_id, "
            + "  SUM(event_name = 'post_like_click') AS like_cnt, "
            + "  SUM(event_name = 'playlist_collect_click') AS collect_cnt, "
            + "  SUM(event_name = 'post_comment_submit') AS comment_cnt, "
            + "  SUM(event_name = 'post_repost_click') AS share_cnt, "
            + "  SUM(event_name = 'recommend_exposure') AS exposure_cnt, "
            + "  SUM(event_name = 'recommend_click') AS click_cnt "
            + "  FROM newmusic.t_track_event WHERE dt = #{date} "
            + "  AND JSON_EXTRACT(props, '$.song_id') IS NOT NULL GROUP BY song_id) x ON x.song_id = d.song_id "
            + "WHERE d.dt = #{date} GROUP BY d.dt, d.song_id")
    int insertDwsSong(@Param("date") LocalDate date);

    @Delete("DELETE FROM newmusic.t_ads_daily_metrics WHERE stat_date = #{date}")
    int deleteAds(@Param("date") LocalDate date);

    @Insert("INSERT INTO newmusic.t_ads_daily_metrics "
            + "(id, stat_date, metric_id, metric_name, metric_value, dimension, dimension_value, extra) "
            + "VALUES (#{id}, #{date}, #{metricId}, #{metricName}, #{value}, #{dimension}, #{dimensionValue}, #{extra}) "
            + "ON DUPLICATE KEY UPDATE metric_name = VALUES(metric_name), "
            + "metric_value = VALUES(metric_value), extra = VALUES(extra)")
    int upsertAds(@Param("id") Long id, @Param("date") LocalDate date,
                  @Param("metricId") String metricId, @Param("metricName") String metricName,
                  @Param("value") BigDecimal value, @Param("dimension") String dimension,
                  @Param("dimensionValue") String dimensionValue, @Param("extra") String extra);
}
