package com.example.newmusic.module.music.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.ArtistQueryReq;
import com.example.newmusic.module.music.dto.ArtistSaveReq;
import com.example.newmusic.module.music.entity.Artist;

public interface ArtistService {

    PageResult<Artist> page(ArtistQueryReq query);

    /** 取歌手，不存在抛 30041（供曲库内部复用）。 */
    Artist require(Long id);

    Artist detail(Long id);

    Long create(ArtistSaveReq req);

    void update(Long id, ArtistSaveReq req);

    void delete(Long id);

    /** 冗余字段维护：歌曲数增减。 */
    void changeSongCount(Long artistId, int delta);
}
