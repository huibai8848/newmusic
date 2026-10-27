package com.example.newmusic.module.dataplatform.mapper;

import com.example.newmusic.module.dataplatform.entity.UserProfileTag;
import com.example.newmusic.module.dataplatform.model.AudienceRow;
import com.example.newmusic.module.dataplatform.model.ChannelRow;
import com.example.newmusic.module.dataplatform.model.DwsAggRow;
import com.example.newmusic.module.dataplatform.model.FirstActiveRow;
import com.example.newmusic.module.dataplatform.model.HistoryAggRow;
import com.example.newmusic.module.dataplatform.model.HourAggRow;
import com.example.newmusic.module.dataplatform.model.LastActiveRow;
import com.example.newmusic.module.dataplatform.model.LatestEnvRow;
import com.example.newmusic.module.dataplatform.model.ProfileUserRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 画像计算的批量取数与标签写入。 */
@Mapper
public interface ProfileQueryMapper {

    @Select("SELECT u.id AS userId, u.gender, u.birthday, u.vip_level AS vipLevel, "
            + "u.create_time AS createTime, p.region, p.follow_count AS followCount, p.fans_count AS fansCount "
            + "FROM newmusic.t_user u LEFT JOIN newmusic.t_user_profile p ON p.user_id = u.id "
            + "WHERE u.deleted = 0 AND u.status = 1 AND u.id > #{fromId} ORDER BY u.id LIMIT #{limit}")
    List<ProfileUserRow> profileUsers(@Param("fromId") long fromId, @Param("limit") int limit);

    @Select("SELECT user_id AS userId, SUM(play_cnt) AS playCnt, SUM(valid_play_cnt) AS validPlayCnt, "
            + "SUM(play_duration) AS playDuration, "
            + "SUM(like_cnt + comment_cnt + follow_cnt + post_cnt) AS interactCnt, "
            + "SUM(room_cnt) AS roomCnt, COUNT(DISTINCT dt) AS activeDays "
            + "FROM newmusic.t_dws_user_daily WHERE dt BETWEEN #{from} AND #{to} GROUP BY user_id")
    List<DwsAggRow> profileDws(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("SELECT user_id AS userId, genre, language, artist_name AS artistName, COUNT(*) AS cnt "
            + "FROM newmusic.t_play_history WHERE create_time >= #{from} AND deleted = 0 "
            + "GROUP BY user_id, genre, language, artist_name LIMIT #{limit}")
    List<HistoryAggRow> profileHistory(@Param("from") LocalDateTime from, @Param("limit") int limit);

    @Select("SELECT user_id AS userId, HOUR(server_time) AS hour, COUNT(*) AS cnt "
            + "FROM newmusic.t_track_event WHERE dt >= #{from} AND user_id IS NOT NULL "
            + "GROUP BY user_id, HOUR(server_time) LIMIT #{limit}")
    List<HourAggRow> profileHours(@Param("from") LocalDate from, @Param("limit") int limit);

    @Select("SELECT userId, platform, os, network FROM ("
            + "SELECT user_id AS userId, platform, os, network, "
            + "ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY server_time DESC) AS rn "
            + "FROM newmusic.t_track_event WHERE dt >= #{from} AND user_id IS NOT NULL) t WHERE rn = 1")
    List<LatestEnvRow> profileLatestEnv(@Param("from") LocalDate from);

    @Select("SELECT user_id AS userId, MAX(JSON_UNQUOTE(JSON_EXTRACT(props, '$.channel'))) AS channel "
            + "FROM newmusic.t_track_event WHERE event_name = 'user_register_success' "
            + "AND user_id IS NOT NULL GROUP BY user_id")
    List<ChannelRow> profileRegisterChannels();

    @Select("SELECT user_id AS userId, MIN(dt) AS firstDt FROM newmusic.t_track_event "
            + "WHERE user_id IS NOT NULL GROUP BY user_id")
    List<FirstActiveRow> profileFirstActive();

    @Select("SELECT user_id AS userId, MAX(dt) AS lastDt FROM newmusic.t_track_event "
            + "WHERE user_id IS NOT NULL GROUP BY user_id")
    List<LastActiveRow> profileLastActive();

    @Select("<script>SELECT user_id AS userId, tag_name AS tagName, tag_value AS tagValue "
            + "FROM newmusic.t_user_profile_tag WHERE tag_name IN "
            + "<foreach collection='tagNames' item='n' open='(' separator=',' close=')'>#{n}</foreach> "
            + "LIMIT #{limit}</script>")
    List<AudienceRow> audienceRows(@Param("tagNames") List<String> tagNames, @Param("limit") int limit);

    /** 清掉该用户上一版、但本次没有再计算的标签（避免旧兴趣标签永远残留）。 */
    @Delete("DELETE FROM newmusic.t_user_profile_tag WHERE user_id = #{userId} AND update_date < #{date}")
    int deleteStaleTags(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Insert("<script>INSERT INTO newmusic.t_user_profile_tag "
            + "(id, user_id, tag_type, tag_name, tag_value, tag_version, update_date) VALUES "
            + "<foreach collection='tags' item='t' separator=','>"
            + "(#{t.id}, #{t.userId}, #{t.tagType}, #{t.tagName}, #{t.tagValue}, #{t.tagVersion}, #{t.updateDate})"
            + "</foreach> ON DUPLICATE KEY UPDATE tag_type = VALUES(tag_type), "
            + "tag_value = VALUES(tag_value), tag_version = VALUES(tag_version), update_date = VALUES(update_date)"
            + "</script>")
    int upsertTags(@Param("tags") List<UserProfileTag> tags);
}
