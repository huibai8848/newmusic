package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.social.entity.LikeRecord;
import com.example.newmusic.module.social.vo.LikeCountRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LikeMapper extends BaseMapper<LikeRecord> {

    /** 唯一索引 + INSERT IGNORE：重复点赞不报错，返回 0。 */
    @Insert("INSERT IGNORE INTO t_like (id, user_id, target_type, target_id, create_time) "
            + "VALUES (#{id}, #{userId}, #{targetType}, #{targetId}, #{createTime})")
    int insertIgnore(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("targetType") String targetType,
                     @Param("targetId") Long targetId,
                     @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_like WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}")
    int deleteByUserAndTarget(@Param("userId") Long userId,
                              @Param("targetType") String targetType,
                              @Param("targetId") Long targetId);

    @Select("SELECT COUNT(*) FROM t_like WHERE target_type = #{targetType} AND target_id = #{targetId}")
    long countByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    @Select("<script>SELECT target_id FROM t_like WHERE user_id = #{userId} AND target_type = #{targetType} "
            + "AND target_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectLikedIds(@Param("userId") Long userId,
                              @Param("targetType") String targetType,
                              @Param("ids") List<Long> ids);

    @Select("<script>SELECT target_id AS targetId, COUNT(*) AS cnt FROM t_like "
            + "WHERE target_type = #{targetType} AND target_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY target_id</script>")
    List<LikeCountRow> countByTargets(@Param("targetType") String targetType,
                                      @Param("ids") List<Long> ids);

    /** 我喜欢的歌曲，按点赞时间倒序。 */
    @Select("SELECT s.* FROM t_like l JOIN t_song s ON s.id = l.target_id "
            + "WHERE l.user_id = #{userId} AND l.target_type = 'SONG' AND s.deleted = 0 "
            + "ORDER BY l.create_time DESC, l.id DESC")
    IPage<Song> selectLikedSongs(IPage<Song> page, @Param("userId") Long userId);
}
