package com.example.newmusic.module.music.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.AlbumQueryReq;
import com.example.newmusic.module.music.dto.AlbumSaveReq;
import com.example.newmusic.module.music.entity.Album;

public interface AlbumService {

    PageResult<Album> page(AlbumQueryReq query);

    /** 取专辑，不存在抛 30042。 */
    Album require(Long id);

    Album detail(Long id);

    Long create(AlbumSaveReq req);

    void update(Long id, AlbumSaveReq req);

    void delete(Long id);

    /** 冗余字段维护：专辑歌曲数增减。 */
    void changeSongCount(Long albumId, int delta);
}
