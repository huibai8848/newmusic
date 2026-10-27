package com.example.newmusic.module.play.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.play.entity.PlayHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {

    /** 最近播放的歌曲 ID（去重），Redis 不可用时的降级数据源 */
    @Select("SELECT song_id FROM t_play_history "
            + "WHERE user_id = #{userId} AND deleted = 0 "
            + "GROUP BY song_id ORDER BY MAX(play_time) DESC LIMIT #{limit}")
    List<Long> selectRecentSongIds(@Param("userId") Long userId, @Param("limit") int limit);

    /** 推荐用：用户听过的全部歌曲 ID（用于控制「老歌重听」占比） */
    @Select("SELECT DISTINCT song_id FROM t_play_history WHERE user_id = #{userId} AND deleted = 0")
    List<Long> selectListenedSongIds(@Param("userId") Long userId);

    /** 最近 30 天播放次数 Top 歌手（FR-17-007 最近常听） */
    @Select("SELECT artist_id AS artistId, MAX(artist_name) AS artistName, COUNT(*) AS playCount, "
            + "MAX(play_time) AS lastPlayTime FROM t_play_history "
            + "WHERE user_id = #{userId} AND deleted = 0 AND artist_id IS NOT NULL "
            + "AND play_time >= #{since} "
            + "GROUP BY artist_id ORDER BY playCount DESC, lastPlayTime DESC LIMIT #{limit}")
    List<FrequentArtistRow> selectFrequentArtists(@Param("userId") Long userId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("limit") int limit);

    /** 严格时间切分用：某用户在 [since, until) 内播放过的去重歌曲。 */
    @Select("SELECT DISTINCT song_id FROM t_play_history WHERE user_id = #{userId} AND deleted = 0 "
            + "AND play_time >= #{since} AND play_time < #{until}")
    List<Long> selectSongIdsBetween(@Param("userId") Long userId,
                                    @Param("since") LocalDateTime since,
                                    @Param("until") LocalDateTime until);

    /** 每日预热任务：最近有行为的用户 ID（去重） */
    @Select("SELECT DISTINCT user_id FROM t_play_history "
            + "WHERE deleted = 0 AND user_id IS NOT NULL AND play_time >= #{since} LIMIT #{limit}")
    List<Long> selectActiveUserIds(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 猜你喜欢的协同信号：与用户听过的歌在同一用户历史中共同出现的歌 */
    @Select("SELECT h2.song_id AS songId, COUNT(*) AS coCount FROM t_play_history h1 "
            + "JOIN t_play_history h2 ON h1.user_id = h2.user_id AND h1.song_id <> h2.song_id "
            + "WHERE h1.user_id = #{userId} AND h1.deleted = 0 AND h2.deleted = 0 "
            + "AND h1.play_time >= #{since} AND h2.play_time >= #{since} "
            + "GROUP BY h2.song_id ORDER BY coCount DESC LIMIT #{limit}")
    List<CoOccurrenceRow> selectCoOccurrences(@Param("userId") Long userId,
                                              @Param("since") LocalDateTime since,
                                              @Param("limit") int limit);

    /** 播放历史列表：每首歌只取最近一次（BR-16-027） */
    @Select("SELECT h.* FROM t_play_history h JOIN ("
            + "  SELECT song_id, MAX(play_time) AS pt FROM t_play_history "
            + "  WHERE user_id = #{userId} AND deleted = 0 GROUP BY song_id "
            + "  ORDER BY pt DESC LIMIT #{offset}, #{size}"
            + ") x ON h.song_id = x.song_id AND h.play_time = x.pt "
            + "WHERE h.user_id = #{userId} AND h.deleted = 0 ORDER BY h.play_time DESC")
    List<PlayHistory> selectLatestPage(@Param("userId") Long userId,
                                       @Param("offset") long offset,
                                       @Param("size") long size);

    /** 去重后的历史条数，用于分页 total */
    @Select("SELECT COUNT(DISTINCT song_id) FROM t_play_history WHERE user_id = #{userId} AND deleted = 0")
    long countDistinctSongs(@Param("userId") Long userId);

    /** 最近一次播放某首歌的历史行，继续播放的 DB 降级用 */
    @Select("SELECT * FROM t_play_history WHERE user_id = #{userId} AND song_id = #{songId} "
            + "AND deleted = 0 ORDER BY play_time DESC LIMIT 1")
    PlayHistory selectLatestBySong(@Param("userId") Long userId, @Param("songId") Long songId);

    /** 最近 90 天行为明细，推荐画像（风格/语种/歌手/标签权重）的数据源 */
    @Select("SELECT * FROM t_play_history WHERE user_id = #{userId} AND deleted = 0 "
            + "AND play_time >= #{since} ORDER BY play_time DESC LIMIT #{limit}")
    List<PlayHistory> selectRecentHistory(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    /** 有效行为条数（BR-17-028 的 5 条阈值按行为条数算） */
    @Select("SELECT COUNT(*) FROM t_play_history WHERE user_id = #{userId} AND deleted = 0")
    long countByUser(@Param("userId") Long userId);

    /** 全站共现：与指定歌曲在同一用户历史中共同出现的歌（相似度计算用） */
    @Select("SELECT h2.song_id AS songId, COUNT(*) AS coCount FROM t_play_history h1 "
            + "JOIN t_play_history h2 ON h1.user_id = h2.user_id AND h1.song_id <> h2.song_id "
            + "WHERE h1.song_id = #{songId} AND h1.deleted = 0 AND h2.deleted = 0 "
            + "AND h1.play_time >= #{since} AND h2.play_time >= #{since} "
            + "GROUP BY h2.song_id ORDER BY coCount DESC LIMIT #{limit}")
    List<CoOccurrenceRow> selectCoOccurrencesForSong(@Param("songId") Long songId,
                                                     @Param("since") LocalDateTime since,
                                                     @Param("limit") int limit);

    /** 继续播放的 DB 候选：进度 10%~90%，按最近更新时间倒序 */
    @Select("SELECT * FROM t_play_history WHERE user_id = #{userId} AND deleted = 0 "
            + "AND song_duration > 0 AND last_position > 0 "
            + "AND last_position / song_duration BETWEEN 0.1 AND 0.9 "
            + "ORDER BY update_time DESC, play_time DESC LIMIT #{limit}")
    List<PlayHistory> selectContinueRows(@Param("userId") Long userId, @Param("limit") int limit);

    /** 好友最近在听：一次取回多个好友的共现歌曲，好友召回用（FR-24-001 的 friend_listen）。 */
    @Select("<script>SELECT song_id AS songId, COUNT(*) AS coCount FROM t_play_history "
            + "WHERE deleted = 0 AND play_time >= #{since} AND user_id IN "
            + "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY song_id ORDER BY coCount DESC LIMIT #{limit}</script>")
    List<CoOccurrenceRow> selectPopularAmongUsers(@Param("userIds") java.util.Collection<Long> userIds,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("limit") int limit);

    /** 单曲行为汇总：播放次数、有效播放次数、平均播放比例，用于 skip/completion 特征。 */
    @Select("SELECT song_id AS songId, COUNT(*) AS coCount, "
            + "SUM(CASE WHEN is_valid = 1 THEN 1 ELSE 0 END) AS validCount, "
            + "AVG(CASE WHEN song_duration > 0 THEN play_duration / song_duration ELSE 0 END) AS avgRatio "
            + "FROM t_play_history WHERE user_id = #{userId} AND deleted = 0 GROUP BY song_id")
    List<SongStatRow> selectSongStats(@Param("userId") Long userId);

    @lombok.Data
    class SongStatRow {
        private Long songId;
        private Long coCount;
        private Long validCount;
        private Double avgRatio;
    }

    @lombok.Data
    class FrequentArtistRow {
        private Long artistId;
        private String artistName;
        private Long playCount;
        private LocalDateTime lastPlayTime;
    }

    @lombok.Data
    class CoOccurrenceRow {
        private Long songId;
        private Long coCount;
    }
}
