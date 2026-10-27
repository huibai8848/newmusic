package com.example.newmusic.module.music.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.contentsafety.service.ContentAuditService;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.music.dto.PlaylistQueryReq;
import com.example.newmusic.module.music.dto.PlaylistSaveReq;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.PlaylistSong;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.PlaylistSongMapper;
import com.example.newmusic.module.music.service.PlaylistService;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.music.vo.PlaylistDetailVO;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlaylistServiceImpl extends BaseCrudService<PlaylistMapper, Playlist> implements PlaylistService {

    private static final Set<String> SORT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "name", "songCount", "playCount", "collectCount", "hotScore",
            "sortWeight", "createTime", "updateTime"));

    /** 歌单管理权限：拥有者可越过「创建者」限制编辑系统/编辑歌单 */
    private static final String PERM_PLAYLIST_MANAGE = "music:playlist:manage";

    private static final int STATUS_NORMAL = 1;
    private static final int PUBLIC = 1;

    private final PlaylistSongMapper playlistSongMapper;
    private final SongService songService;
    private final ContentAuditService contentAuditService;
    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    /** 模块 06/11：ES 模式下增量同步索引（未启用 ES 时为空操作）。 */
    private final SearchIndexSyncService searchIndexSyncService;

    public PlaylistServiceImpl(PlaylistSongMapper playlistSongMapper, SongService songService,
                               ContentAuditService contentAuditService,
                               UserRestrictionService restrictionService,
                               MinorProtectionService minorService,
                               SearchIndexSyncService searchIndexSyncService) {
        this.playlistSongMapper = playlistSongMapper;
        this.songService = songService;
        this.contentAuditService = contentAuditService;
        this.restrictionService = restrictionService;
        this.minorService = minorService;
        this.searchIndexSyncService = searchIndexSyncService;
    }

    @Override
    public PageResult<Playlist> page(PlaylistQueryReq query) {
        QueryWrapper<Playlist> wrapper = Wrappers.query();
        Long currentUserId = UserContext.getUserId();
        Long effectiveUserId = Boolean.TRUE.equals(query.getMine())
                ? UserContext.requireUserId()
                : query.getUserId();
        boolean self = effectiveUserId != null && effectiveUserId.equals(currentUserId);
        boolean manage = UserContext.hasPermission(PERM_PLAYLIST_MANAGE);

        if (effectiveUserId != null) {
            wrapper.eq("user_id", effectiveUserId);
        }
        wrapper.eq(query.getPlaylistType() != null, "playlist_type", query.getPlaylistType());
        if (query.getIsPublic() != null) {
            wrapper.eq("is_public", query.getIsPublic());
        }
        wrapper.eq(query.getIsRecommend() != null, "is_recommend", query.getIsRecommend());
        if (StrUtil.isNotBlank(query.getTag())) {
            wrapper.apply("FIND_IN_SET({0}, tags)", query.getTag().trim());
        }
        if (!self && !manage) {
            // 非本人列表：仅公开且正常状态
            wrapper.eq("is_public", PUBLIC).eq("status", STATUS_NORMAL);
        }
        if (query.hasKeyword()) {
            wrapper.like("name", LikeUtil.escape(query.getKeyword()));
        }
        return PageResult.of(pageQuery(query, wrapper, SORT_FIELDS), query.isTruncated());
    }

    @Override
    public PlaylistDetailVO detail(Long id, int pageNum, int pageSize) {
        Playlist playlist = require(requireId(id));
        checkReadable(playlist);
        PageResult<Song> songs = songService.pageByPlaylist(playlist.getId(), pageNum, pageSize);
        return PlaylistDetailVO.of(playlist, songs);
    }

    @Override
    public Playlist require(Long id) {
        Playlist entity = id == null ? null : getById(id);
        if (entity == null) {
            throw new BizException(ResultCode.PLAYLIST_NOT_FOUND);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(PlaylistSaveReq req) {
        Long userId = UserContext.requireUserId();
        Playlist entity = new Playlist();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        entity.setVersion(null);
        entity.setUserId(userId);
        entity.setSongCount(0);
        entity.setPlayCount(0L);
        entity.setCollectCount(0L);
        entity.setCommentCount(0L);
        entity.setShareCount(0L);
        entity.setHotScore(0);
        entity.setIsRecommend(0);
        entity.setSortWeight(0);
        entity.setAuditStatus(1);
        entity.setStatus(STATUS_NORMAL);
        // 前台只允许创建用户歌单；算法/场景/编辑歌单由后台或推荐服务创建
        entity.setPlaylistType(Playlist.TYPE_USER);
        if (UserContext.hasPermission(PERM_PLAYLIST_MANAGE) && req.getPlaylistType() != null) {
            entity.setPlaylistType(req.getPlaylistType());
        }
        if (entity.getIsPublic() == null) {
            entity.setIsPublic(PUBLIC);
        }
        if (!UserContext.hasPermission(PERM_PLAYLIST_MANAGE)) {
            restrictionService.requireCanPublish(userId);
            minorService.requireSocialAllowed(userId);
        }
        AuditRequest auditRequest = auditRequest(userId, req.getName(), req.getDescription(),
                "PLAYLIST");
        AuditResult audit = contentAuditService.audit(auditRequest);
        if (audit.isReject()) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL, "歌单名称或简介包含违规内容");
        }
        if (audit.isReview()) {
            entity.setAuditStatus(0);
            entity.setStatus(0);
        }
        save(entity);
        contentAuditService.attachContent(audit, auditRequest, entity.getId());
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncPlaylist(entity.getId()));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PlaylistSaveReq req) {
        requireVersion(req.getVersion());
        Playlist exists = require(id);
        checkEditable(exists);
        Playlist entity = new Playlist();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        // 创建者与类型不可改
        entity.setUserId(null);
        entity.setPlaylistType(null);
        entity.setSongCount(null);
        entity.setPlayCount(null);
        entity.setCollectCount(null);
        entity.setCommentCount(null);
        entity.setShareCount(null);
        entity.setHotScore(null);
        // AI 标识不允许通过普通编辑移除（DTO 无字段，这里再兜一层）。
        if (Integer.valueOf(1).equals(exists.getAiGenerated())) {
            entity.setAiGenerated(null);
            entity.setAiModel(null);
            entity.setAiPromptVersion(null);
            entity.setAiGeneratedAt(null);
        }
        AuditRequest auditRequest = auditRequest(exists.getUserId(), req.getName(),
                req.getDescription(), "PLAYLIST_EDIT");
        AuditResult audit = contentAuditService.audit(auditRequest);
        if (audit.isReject()) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL, "歌单名称或简介包含违规内容");
        }
        if (audit.isReview()) {
            entity.setAuditStatus(0);
            entity.setStatus(0);
        }
        updateWithVersion(entity);
        contentAuditService.attachContent(audit, auditRequest, id);
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncPlaylist(id));
    }

    private static AuditRequest auditRequest(Long userId, String name, String description,
                                             String scene) {
        return AuditRequest.of("PLAYLIST", null,
                (name == null ? "" : name) + "\n" + (description == null ? "" : description),
                userId, scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Playlist exists = require(id);
        checkEditable(exists);
        removeSafe(exists.getId());
        // 级联：歌单歌曲关联物理删除（BR-10-024）
        playlistSongMapper.delete(Wrappers.<PlaylistSong>query().eq("playlist_id", exists.getId()));
        // 删除后 syncPlaylist 查不到实体，会走「从索引移除」那个分支
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncPlaylist(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addSongs(Long playlistId, List<Long> songIds) {
        Playlist playlist = require(playlistId);
        checkEditable(playlist);
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        if (songIds != null) {
            songIds.stream().filter(Objects::nonNull).forEach(targets::add);
        }
        if (targets.isEmpty()) {
            return 0;
        }
        List<PlaylistSong> existing = listLinks(playlistId);
        Set<Long> existingIds = existing.stream().map(PlaylistSong::getSongId).collect(Collectors.toSet());
        List<Long> toAdd = targets.stream().filter(sid -> !existingIds.contains(sid)).collect(Collectors.toList());
        if (toAdd.isEmpty()) {
            return 0; // BR-04-011：重复加歌幂等
        }
        if (existing.size() + toAdd.size() > Playlist.MAX_SONG_COUNT) {
            throw new BizException(ResultCode.PLAYLIST_SONG_LIMIT,
                    "歌单最多 " + Playlist.MAX_SONG_COUNT + " 首，当前 " + existing.size() + " 首");
        }
        // 歌曲必须存在，避免脏关联
        List<Song> songs = songService.listByIdsOrdered(toAdd);
        if (songs.size() != toAdd.size()) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }

        // 顺序号接在现有末尾之后，按步长 100 递增（BR-04-012）
        int order = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder();
        Long userId = UserContext.requireUserId();
        for (Long songId : toAdd) {
            order += PlaylistSong.SORT_STEP;
            PlaylistSong link = new PlaylistSong();
            link.setPlaylistId(playlistId);
            link.setSongId(songId);
            link.setSortOrder(order);
            link.setAddBy(userId);
            playlistSongMapper.insert(link);
        }
        baseMapper.updateSongCount(playlistId, toAdd.size());
        // 歌曲数是歌单文档里的字段，数量变了要重写（搜「100 首歌单」之类的场景）
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncPlaylist(playlistId));
        return toAdd.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeSongs(Long playlistId, List<Long> songIds) {
        Playlist playlist = require(playlistId);
        checkEditable(playlist);
        if (CollUtil.isEmpty(songIds)) {
            return 0;
        }
        List<Long> distinct = songIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        int removed = playlistSongMapper.delete(Wrappers.<PlaylistSong>query()
                .eq("playlist_id", playlistId).in("song_id", distinct));
        if (removed > 0) {
            baseMapper.updateSongCount(playlistId, -removed);
            SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncPlaylist(playlistId));
        }
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long playlistId, List<Long> songIds) {
        Playlist playlist = require(playlistId);
        checkEditable(playlist);
        List<PlaylistSong> links = listLinks(playlistId);
        if (links.isEmpty()) {
            return;
        }
        Set<Long> existingIds = links.stream().map(PlaylistSong::getSongId).collect(Collectors.toSet());

        // 1) 按请求顺序收集（忽略不在歌单内的 ID 与重复项）
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        if (songIds != null) {
            songIds.stream().filter(Objects::nonNull)
                    .filter(existingIds::contains).forEach(ordered::add);
        }
        // 2) 请求中遗漏的歌曲追加到末尾，避免拖拽丢歌
        for (PlaylistSong link : links) {
            ordered.add(link.getSongId());
        }

        // 3) 整体重排，恢复步长 100 的稀疏结构
        int order = PlaylistSong.SORT_STEP;
        for (Long songId : ordered) {
            playlistSongMapper.updateSortOrder(playlistId, songId, order);
            order += PlaylistSong.SORT_STEP;
        }
        log.debug("歌单重排完成 playlistId={} size={}", playlistId, ordered.size());
    }

    private List<PlaylistSong> listLinks(Long playlistId) {
        return playlistSongMapper.selectList(Wrappers.<PlaylistSong>query()
                .eq("playlist_id", playlistId)
                .orderByAsc("sort_order").orderByAsc("id"));
    }

    /** 读权限：公开歌单所有人可读；私密歌单仅创建者或管理员。 */
    private void checkReadable(Playlist playlist) {
        Long currentUserId = UserContext.getUserId();
        boolean self = currentUserId != null && currentUserId.equals(playlist.getUserId());
        if (Objects.equals(playlist.getIsPublic(), PUBLIC) || self
                || UserContext.hasPermission(PERM_PLAYLIST_MANAGE)) {
            return;
        }
        // 不暴露私密歌单的存在性
        throw new BizException(ResultCode.PLAYLIST_NOT_FOUND);
    }

    /**
     * 写权限（BR-04-013 的落地）：
     * <ul>
     *   <li>用户歌单：仅创建者可改；</li>
     *   <li>编辑/算法/场景歌单（user_id=0 或非本人）：需 {@code music:playlist:manage}。</li>
     * </ul>
     */
    private void checkEditable(Playlist playlist) {
        if (UserContext.hasPermission(PERM_PLAYLIST_MANAGE)) {
            return;
        }
        Long currentUserId = UserContext.getUserId();
        boolean self = currentUserId != null && currentUserId.equals(playlist.getUserId());
        if (Objects.equals(playlist.getPlaylistType(), Playlist.TYPE_USER)) {
            if (!self) {
                throw new BizException(ResultCode.NO_PERMISSION_EDIT_PLAYLIST);
            }
            return;
        }
        throw new BizException(ResultCode.NO_PERMISSION_EDIT_PLAYLIST);
    }
}
