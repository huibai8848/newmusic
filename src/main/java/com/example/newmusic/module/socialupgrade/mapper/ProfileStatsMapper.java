package com.example.newmusic.module.socialupgrade.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 主页听歌统计（t_play_history / t_song 聚合，只读）。 */
@Mapper
public interface ProfileStatsMapper {

    @Select("SELECT COALESCE(SUM(play_duration), 0) FROM t_play_history "
            + "WHERE user_id = #{userId} AND deleted = 0")
    long totalPlaySeconds(@Param("userId") Long userId);

    @Select("SELECT COUNT(DISTINCT DATE(play_time)) FROM t_play_history "
            + "WHERE user_id = #{userId} AND deleted = 0")
    int listenDays(@Param("userId") Long userId);

    @Select("SELECT COUNT(DISTINCT song_id) FROM t_play_history WHERE user_id = #{userId} "
            + "AND deleted = 0 AND play_time >= #{since}")
    int distinctSongsSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Select("SELECT artist_id AS artistId, MAX(artist_name) AS artistName, COUNT(*) AS playCount "
            + "FROM t_play_history WHERE user_id = #{userId} AND deleted = 0 "
            + "AND artist_id IS NOT NULL AND play_time >= #{since} "
            + "GROUP BY artist_id ORDER BY playCount DESC LIMIT #{limit}")
    List<ArtistStat> topArtists(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                                @Param("limit") int limit);

    @Select("SELECT s.genre AS genre, COUNT(*) AS playCount FROM t_play_history h "
            + "JOIN t_song s ON s.id = h.song_id WHERE h.user_id = #{userId} AND h.deleted = 0 "
            + "AND h.play_time >= #{since} AND s.genre IS NOT NULL "
            + "GROUP BY s.genre ORDER BY playCount DESC LIMIT #{limit}")
    List<GenreStat> genreDistribution(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                                      @Param("limit") int limit);

    @Data
    class ArtistStat {
        private Long artistId;
        private String artistName;
        private Long playCount;
    }

    @Data
    class GenreStat {
        private String genre;
        private Long playCount;
    }
}
