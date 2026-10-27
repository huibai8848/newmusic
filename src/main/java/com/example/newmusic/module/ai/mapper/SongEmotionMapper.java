package com.example.newmusic.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ai.entity.SongEmotion;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 情绪标注查询。
 *
 * <p>「待标注」的判定是 **LEFT JOIN 后 emotion.id IS NULL**，而不是先查全部歌曲再逐首查表：
 * 后者在 10 万首歌上就是 10 万次查询，批处理还没开始就把连接池耗光了。
 */
@Mapper
public interface SongEmotionMapper extends BaseMapper<SongEmotion> {

    @Select("SELECT s.id AS songId, s.name AS songName, s.artist_name AS artistName, "
            + "s.language AS language, s.lyric_id AS lyricId "
            + "FROM t_song s LEFT JOIN t_song_emotion e ON e.song_id = s.id AND e.deleted = 0 "
            + "WHERE s.deleted = 0 AND s.status = 1 AND s.audit_status = 1 AND e.id IS NULL "
            + "ORDER BY s.publish_time DESC, s.id DESC LIMIT #{limit}")
    List<AnnotationCandidate> selectAnnotationCandidates(@Param("limit") int limit);

    @Select("SELECT e.song_id AS songId, s.name AS songName, e.confidence AS confidence, "
            + "e.emotion_tags AS emotionTags, e.scene_tags AS sceneTags, "
            + "e.reason AS reason, e.update_time AS updateTime "
            + "FROM t_song_emotion e JOIN t_song s ON s.id = e.song_id AND s.deleted = 0 "
            + "WHERE e.deleted = 0 AND e.source = 'AI' AND e.confidence < #{threshold} "
            + "ORDER BY e.confidence ASC, e.update_time ASC LIMIT #{limit}")
    List<LowConfidenceRow> selectLowConfidence(@Param("threshold") BigDecimal threshold,
                                               @Param("limit") int limit);

    @Select("SELECT s.id AS songId, s.name AS songName, s.language AS language, s.lyric_id AS lyricId "
            + "FROM t_song s JOIN t_lyric l ON l.id = s.lyric_id AND l.deleted = 0 "
            + "WHERE s.deleted = 0 AND s.status = 1 AND s.audit_status = 1 "
            + "AND l.audit_status = 1 AND l.content IS NOT NULL AND l.content <> '' "
            + "AND (s.has_translation = 0 OR s.has_translation IS NULL) "
            + "AND (l.translation IS NULL OR l.translation = '') "
            + "AND s.language IS NOT NULL AND s.language <> '' "
            + "AND s.language NOT LIKE '%中文%' AND s.language NOT LIKE '%国语%' "
            + "AND s.language NOT LIKE '%普通话%' "
            + "ORDER BY s.hot_score DESC, s.play_count DESC LIMIT #{limit}")
    List<TranslationCandidate> selectTranslationCandidates(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_song_emotion WHERE deleted = 0")
    long countAnnotated();

    @Data
    class AnnotationCandidate {
        private Long songId;
        private String songName;
        private String artistName;
        private String language;
        private Long lyricId;
    }

    @Data
    class LowConfidenceRow {
        private Long songId;
        private String songName;
        private BigDecimal confidence;
        private String emotionTags;
        private String sceneTags;
        private String reason;
        private LocalDateTime updateTime;
    }

    @Data
    class TranslationCandidate {
        private Long songId;
        private String songName;
        private String language;
        private Long lyricId;
    }
}
