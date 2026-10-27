package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Song;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SongMapper extends BaseMapper<Song> {

    /**
     * 歌曲计数累加（播放/点赞/收藏等）。
     *
     * <p>统计字段不允许走普通更新接口（BR-10-004）；模块 07/10 通过本方法写入，
     * 模块 03 接入 Redis 后改为「Redis 累加 + 定时落库」。
     *
     * @param column 仅允许内部常量传入，禁止外部字符串拼接
     */
    @Update("UPDATE t_song SET ${column} = ${column} + #{delta} WHERE id = #{songId} AND deleted = 0")
    int incrementCounter(@Param("songId") Long songId, @Param("column") String column, @Param("delta") long delta);
}
