package com.example.newmusic.module.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.music.entity.Lyric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LyricMapper extends BaseMapper<Lyric> {

    /**
     * 按歌曲查歌词，**包含已逻辑删除的行**。
     *
     * <p>`uk_lyric_song(song_id)` 是唯一键，逻辑删除不会释放它。如果只查
     * `deleted=0`，删除歌词后再写入会以为「没有歌词」→ 直接 INSERT → 撞唯一键
     * （99001）。这个查询用于识别并恢复那条墓碑行。
     *
     * <p>普通一致性读即可：墓碑行在本事务开始前就已经提交，快照里一定可见。
     */
    @Select("SELECT * FROM t_lyric WHERE song_id = #{songId} ORDER BY id DESC LIMIT 1")
    Lyric selectBySongIdIncludeDeleted(@Param("songId") Long songId);

    /**
     * 并发首写失败后的兜底查询：用当前读（`FOR UPDATE`）绕过 REPEATABLE READ 快照，
     * 否则另一事务刚提交的歌词在本事务里仍然「看不见」，恢复逻辑会误判为空。
     */
    @Select("SELECT * FROM t_lyric WHERE song_id = #{songId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    Lyric selectBySongIdIncludeDeletedForUpdate(@Param("songId") Long songId);

    /**
     * 恢复墓碑行并覆盖正文/类型。
     *
     * <p>只改正文与类型（翻译/罗马音/偏移保持原值），与 `upsertContent` 的覆盖语义一致；
     * `version + 1` 让并发编辑仍能通过乐观锁发现冲突。返回 0 表示已被别的请求恢复。
     */
    @Update("UPDATE t_lyric SET content = #{content}, content_type = #{contentType}, "
            + "deleted = 0, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND deleted = 1")
    int reviveContent(@Param("id") Long id,
                      @Param("content") String content,
                      @Param("contentType") Integer contentType);
}
