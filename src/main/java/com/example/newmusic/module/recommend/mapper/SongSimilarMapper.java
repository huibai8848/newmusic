package com.example.newmusic.module.recommend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.recommend.entity.SongSimilar;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SongSimilarMapper extends BaseMapper<SongSimilar> {

    /**
     * 物理删除一首歌的旧相似关系。
     *
     * <p>不能走 {@code BaseMapper.delete}：{@link SongSimilar} 继承 {@link com.example.newmusic.common.entity.BaseEntity}
     * 带有 {@code @TableLogic}，逻辑删除后唯一键 {@code uk_similar_pair(song_id, similar_song_id)}
     * 仍占着位置，第二天重算插入会撞唯一键。
     */
    @Delete("DELETE FROM t_song_similar WHERE song_id = #{songId}")
    int deleteBySongPhysically(@Param("songId") Long songId);
}
