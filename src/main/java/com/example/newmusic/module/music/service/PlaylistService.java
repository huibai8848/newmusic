package com.example.newmusic.module.music.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.PlaylistQueryReq;
import com.example.newmusic.module.music.dto.PlaylistSaveReq;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.vo.PlaylistDetailVO;

import java.util.List;

public interface PlaylistService {

    PageResult<Playlist> page(PlaylistQueryReq query);

    /** 歌单详情 + 歌曲分页（私密歌单仅创建者/管理员可见）。 */
    PlaylistDetailVO detail(Long id, int pageNum, int pageSize);

    /** 取歌单，不存在抛 30004。 */
    Playlist require(Long id);

    Long create(PlaylistSaveReq req);

    void update(Long id, PlaylistSaveReq req);

    void delete(Long id);

    /** 加歌，返回实际新增数量（重复歌曲静默跳过，BR-04-011）。 */
    int addSongs(Long playlistId, List<Long> songIds);

    /** 移歌，返回实际移除数量。 */
    int removeSongs(Long playlistId, List<Long> songIds);

    /** 重排（BR-04-012：按传入顺序以步长 100 整体重排 sort_order）。 */
    void reorder(Long playlistId, List<Long> songIds);
}
