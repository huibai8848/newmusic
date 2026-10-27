package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.Topic;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

    @Select("SELECT * FROM t_topic WHERE name = #{name}")
    Topic selectByName(@Param("name") String name);

    @Insert("INSERT IGNORE INTO t_topic (id, name, post_count, view_count, hot_score, is_official, status, "
            + "create_time, update_time) VALUES (#{id}, #{name}, 0, 0, 0, 0, 0, NOW(3), NOW(3))")
    int insertIgnore(@Param("id") Long id, @Param("name") String name);

    @Update("UPDATE t_topic SET post_count = post_count + #{delta}, "
            + "hot_score = hot_score + #{delta} WHERE name = #{name}")
    int incrPostCount(@Param("name") String name, @Param("delta") int delta);

    @Select("SELECT * FROM t_topic WHERE status = 0 ORDER BY hot_score DESC, post_count DESC LIMIT #{limit}")
    List<Topic> selectHot(@Param("limit") int limit);

    @Select("SELECT * FROM t_topic WHERE status = 0 AND name LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY post_count DESC LIMIT #{limit}")
    List<Topic> search(@Param("keyword") String keyword, @Param("limit") int limit);
}
