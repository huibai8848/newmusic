package com.example.newmusic.module.music.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.SongQueryReq;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.entity.Song;

import java.util.Collection;
import java.util.List;

public interface SongService {

    /** 后台列表：可按 status/auditStatus 过滤，含未上架歌曲。 */
    PageResult<Song> page(SongQueryReq query);

    /** 前台列表：强制 status=1 且 audit_status=1（BR-04-001）。 */
    PageResult<Song> pagePublished(SongQueryReq query);

    /** 按歌单顺序取歌曲（内部按 t_playlist_song.sort_order 排序）。 */
    PageResult<Song> pageByPlaylist(Long playlistId, int pageNum, int pageSize);

    Song detail(Long id);

    /** 播放前校验：存在 + 已上架 + 审核通过 + 版权有效；否则抛 30001/30002/30008。 */
    Song requirePlayable(Long id);

    List<Song> listByIdsOrdered(Collection<Long> ids);

    Long create(SongSaveReq req);

    void update(Long id, SongSaveReq req);

    /** 上架/下架（校验 BR-04-001/002）。 */
    void changeStatus(Long id, Integer status);

    void audit(Long id, Integer auditStatus);

    void delete(Long id);

    void batchDelete(List<Long> ids);

    /** 统计字段自增（播放/点赞等），column 仅允许白名单常量。 */
    void incrementCounter(Long songId, String column, long delta);

    /**
     * 全部**未逻辑删除**歌曲的 ID，供模块 03 的布隆过滤器预热使用。
     *
     * <p>刻意不含墓碑（{@code deleted=1}）行：布隆过滤器不支持删除，若把墓碑 ID 也塞进去，
     * 对已删歌曲的请求会走到「布隆说可能存在 → 回源 → 查不到 → 写空值缓存」，
     * 白多一次回源。不塞进去，它们就直接命中「一定不存在」，而这与 DB 的答案（查不到）一致。
     *
     * <p>生产数据量大时应改为分批游标，并放在应用启动后的后台线程里，不要挂在请求路径上。
     */
    List<Long> listAllIdsForBloom();
}
