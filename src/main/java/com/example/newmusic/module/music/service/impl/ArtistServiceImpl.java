package com.example.newmusic.module.music.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.module.music.dto.ArtistQueryReq;
import com.example.newmusic.module.music.dto.ArtistSaveReq;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.support.MusicPinyin;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.service.ArtistService;
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
public class ArtistServiceImpl extends BaseCrudService<ArtistMapper, Artist> implements ArtistService {

    private static final Set<String> SORT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "name", "songCount", "albumCount", "followerCount", "hotScore", "createTime"));

    private final SongMapper songMapper;

    /** 模块 06/11：ES 模式下增量同步索引（未启用 ES 时为空操作）。 */
    private final SearchIndexSyncService searchIndexSyncService;

    public ArtistServiceImpl(SongMapper songMapper, SearchIndexSyncService searchIndexSyncService) {
        this.songMapper = songMapper;
        this.searchIndexSyncService = searchIndexSyncService;
    }

    @Override
    public PageResult<Artist> page(ArtistQueryReq query) {
        QueryWrapper<Artist> wrapper = Wrappers.query();
        wrapper.eq(query.getStatus() != null, "status", query.getStatus());
        wrapper.eq(StrUtil.isNotBlank(query.getCountry()), "country", query.getCountry());
        wrapper.eq(StrUtil.isNotBlank(query.getFirstLetter()), "first_letter", query.getFirstLetter());
        if (query.hasKeyword()) {
            String kw = LikeUtil.escape(query.getKeyword());
            wrapper.and(w -> w.like("name", kw).or().like("alias", kw));
        }
        return PageResult.of(pageQuery(query, wrapper, SORT_FIELDS), query.isTruncated());
    }

    @Override
    public Artist require(Long id) {
        Artist entity = id == null ? null : getById(id);
        if (entity == null) {
            throw new BizException(ResultCode.ARTIST_NOT_FOUND);
        }
        return entity;
    }

    @Override
    public Artist detail(Long id) {
        return require(requireId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArtistSaveReq req) {
        Long dup = count(Wrappers.<Artist>query().eq("name", req.getName()));
        if (dup != null && dup > 0) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "歌手名已存在：" + req.getName());
        }
        Artist entity = new Artist();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        entity.setSongCount(0);
        entity.setAlbumCount(0);
        entity.setFollowerCount(0L);
        entity.setHotScore(0);
        if (StrUtil.isBlank(entity.getFirstLetter())) {
            entity.setFirstLetter(extractFirstLetter(req.getName()));
        }
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        // 拼音检索列（模块 06 D-35）
        MusicPinyin.fill(entity);
        save(entity);
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncArtist(entity.getId()));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArtistSaveReq req) {
        requireVersion(req.getVersion());
        Artist exists = detail(id);
        if (StrUtil.isNotBlank(req.getName()) && !exists.getName().equals(req.getName())) {
            Long dup = count(Wrappers.<Artist>query().eq("name", req.getName()).ne("id", id));
            if (dup != null && dup > 0) {
                throw new BizException(ResultCode.DUPLICATE_DATA, "歌手名已存在：" + req.getName());
            }
        }
        Artist entity = new Artist();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        if (StrUtil.isBlank(entity.getFirstLetter())) {
            entity.setFirstLetter(extractFirstLetter(req.getName()));
        }
        // 拼音检索列（模块 06 D-35）。名字没带时 req.getName() 为 null，
        // MusicPinyin 会写 null，MyBatis-Plus 的更新策略对 null 是跳过，原值因此保留
        MusicPinyin.fill(entity);
        updateWithVersion(entity);
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncArtist(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Artist exists = detail(id);
        Long songs = songMapper.selectCount(Wrappers.<Song>query().eq("artist_id", exists.getId()));
        if (songs != null && songs > 0) {
            throw new BizException(ResultCode.ARTIST_HAS_SONGS);
        }
        removeSafe(exists.getId());
        // 删除后 syncArtist 查不到实体，会走「从索引移除」那个分支
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncArtist(id));
    }

    @Override
    public void changeSongCount(Long artistId, int delta) {
        if (artistId == null || delta == 0) {
            return;
        }
        baseMapper.updateSongCount(artistId, delta);
    }

    /**
     * 首字母：ASCII 字母直接取大写，中文取 Unicode 区间映射（不引入拼音库，D-11）。
     *
     * <p>曲库录入时建议显式传入 first_letter，本方法仅作兜底。
     */
    private String extractFirstLetter(String name) {
        if (StrUtil.isBlank(name)) {
            return "#";
        }
        char c = name.trim().charAt(0);
        if (c >= 'a' && c <= 'z') {
            return String.valueOf((char) (c - 32));
        }
        if (c >= 'A' && c <= 'Z') {
            return String.valueOf(c);
        }
        return "#";
    }
}
