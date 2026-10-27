package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 标签使用次数增减（冗余字段维护）。 */
    @Update("UPDATE t_tag SET use_count = GREATEST(CAST(use_count AS SIGNED) + #{delta}, 0) "
            + "WHERE id = #{tagId} AND deleted = 0")
    int updateUseCount(@Param("tagId") Long tagId, @Param("delta") int delta);
}
