package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.CollectFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CollectFolderMapper extends BaseMapper<CollectFolder> {

    @Update("UPDATE t_collect_folder SET item_count = GREATEST(item_count + #{delta}, 0) WHERE id = #{id}")
    int updateItemCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_collect_folder SET item_count = #{count} WHERE id = #{id}")
    int resetItemCount(@Param("id") Long id, @Param("count") int count);
}
