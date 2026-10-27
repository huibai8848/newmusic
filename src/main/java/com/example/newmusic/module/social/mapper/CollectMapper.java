package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.Collect;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CollectMapper extends BaseMapper<Collect> {

    @Insert("INSERT IGNORE INTO t_collect (id, user_id, target_type, target_id, folder_id, create_time) "
            + "VALUES (#{id}, #{userId}, #{targetType}, #{targetId}, #{folderId}, #{createTime})")
    int insertIgnore(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("targetType") String targetType,
                     @Param("targetId") Long targetId,
                     @Param("folderId") Long folderId,
                     @Param("createTime") LocalDateTime createTime);

    @Delete("DELETE FROM t_collect WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}")
    int deleteByUserAndTarget(@Param("userId") Long userId,
                              @Param("targetType") String targetType,
                              @Param("targetId") Long targetId);

    @Select("SELECT COUNT(*) FROM t_collect WHERE target_type = #{targetType} AND target_id = #{targetId}")
    long countByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    @Update("UPDATE t_collect SET folder_id = #{folderId} WHERE user_id = #{userId} AND id = #{id}")
    int moveToFolder(@Param("id") Long id, @Param("userId") Long userId, @Param("folderId") Long folderId);

    @Update("UPDATE t_collect SET folder_id = #{toFolderId} WHERE user_id = #{userId} AND folder_id = #{fromFolderId}")
    int moveAll(@Param("userId") Long userId,
                @Param("fromFolderId") Long fromFolderId,
                @Param("toFolderId") Long toFolderId);
}
