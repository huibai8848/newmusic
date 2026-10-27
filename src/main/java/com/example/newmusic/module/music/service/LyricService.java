package com.example.newmusic.module.music.service;

import com.example.newmusic.module.music.dto.LyricSaveReq;
import com.example.newmusic.module.music.entity.Lyric;

public interface LyricService {

    /** 按歌曲取歌词；不存在返回 null（播放器显示「暂无歌词」，不报错）。 */
    Lyric getBySongId(Long songId);

    /** 按主键取歌词，不存在抛 30007。 */
    Lyric detail(Long id);

    Long create(LyricSaveReq req);

    void update(Long id, LyricSaveReq req);

    /**
     * 按歌曲写入歌词正文：没有就建，有就覆盖正文与内容类型（翻译/罗马音等其它字段保持不动）。
     *
     * <p>给「歌曲导入时顺带带歌词」用：导入接口的调用方只需要正文，不该被迫先查一次歌词 ID 再决定调 create 还是 update。
     *
     * @param contentType 为空时按正文是否含 `[mm:ss` 时间轴自动判断（1 标准 LRC / 3 纯文本）
     * @return 歌词 ID
     */
    Long upsertContent(Long songId, String content, Integer contentType);

    void delete(Long id);

    /** 歌曲删除时级联清理歌词。 */
    void removeBySongId(Long songId);
}
