package com.example.newmusic.module.music.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.module.music.dto.AlbumQueryReq;
import com.example.newmusic.module.music.dto.AlbumSaveReq;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.support.MusicPinyin;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.service.AlbumService;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class AlbumServiceImpl extends BaseCrudService<AlbumMapper, Album> implements AlbumService {

    private static final Set<String> SORT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "name", "publishDate", "songCount", "hotScore", "createTime"));

    private final ArtistMapper artistMapper;
    private final SongMapper songMapper;

    /** 模块 06/11：ES 模式下增量同步索引（未启用 ES 时为空操作）。 */
    private final SearchIndexSyncService searchIndexSyncService;

    public AlbumServiceImpl(ArtistMapper artistMapper, SongMapper songMapper,
                            SearchIndexSyncService searchIndexSyncService) {
        this.artistMapper = artistMapper;
        this.songMapper = songMapper;
        this.searchIndexSyncService = searchIndexSyncService;
    }

    @Override
    public PageResult<Album> page(AlbumQueryReq query) {
        QueryWrapper<Album> wrapper = Wrappers.query();
        wrapper.eq(query.getArtistId() != null, "artist_id", query.getArtistId());
        wrapper.eq(query.getStatus() != null, "status", query.getStatus());
        if (query.hasKeyword()) {
            wrapper.like("name", LikeUtil.escape(query.getKeyword()));
        }
        return PageResult.of(pageQuery(query, wrapper, SORT_FIELDS), query.isTruncated());
    }

    @Override
    public Album require(Long id) {
        Album entity = id == null ? null : getById(id);
        if (entity == null) {
            throw new BizException(ResultCode.ALBUM_NOT_FOUND);
        }
        return entity;
    }

    @Override
    public Album detail(Long id) {
        return require(requireId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AlbumSaveReq req) {
        Artist artist = artistMapper.selectById(req.getArtistId());
        if (artist == null) {
            throw new BizException(ResultCode.ARTIST_NOT_FOUND);
        }
        Album entity = new Album();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        // 冗余歌手名，列表页免 join
        entity.setArtistName(artist.getName());
        entity.setSongCount(0);
        entity.setHotScore(0);
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        // 拼音检索列（模块 06 D-35）
        MusicPinyin.fill(entity);
        save(entity);
        artistMapper.updateAlbumCount(artist.getId(), 1);
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncAlbum(entity.getId()));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AlbumSaveReq req) {
        requireVersion(req.getVersion());
        Album exists = detail(id);
        Artist artist = artistMapper.selectById(req.getArtistId());
        if (artist == null) {
            throw new BizException(ResultCode.ARTIST_NOT_FOUND);
        }
        Album entity = new Album();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        entity.setArtistName(artist.getName());
        // 拼音检索列（模块 06 D-35）。名字没带时为 null，更新策略会跳过，原值保留
        MusicPinyin.fill(entity);
        updateWithVersion(entity);
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncAlbum(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Album exists = detail(id);
        Long songs = songMapper.selectCount(Wrappers.<Song>query().eq("album_id", exists.getId()));
        if (songs != null && songs > 0) {
            throw new BizException(ResultCode.ALBUM_HAS_SONGS);
        }
        removeSafe(exists.getId());
        artistMapper.updateAlbumCount(exists.getArtistId(), -1);
        // 删除后 syncAlbum 查不到实体，会走「从索引移除」那个分支
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncAlbum(id));
    }

    @Override
    public void changeSongCount(Long albumId, int delta) {
        if (albumId == null || delta == 0) {
            return;
        }
        baseMapper.updateSongCount(albumId, delta);
    }
}
