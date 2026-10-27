package com.example.newmusic.module.music.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.cache.BloomSupport;
import com.example.newmusic.infra.cache.CacheService;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.dto.SongQueryReq;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.PlaylistSong;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.entity.SongTag;
import com.example.newmusic.module.music.event.SongSubmittedForAuditEvent;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.PlaylistSongMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.mapper.SongTagMapper;
import com.example.newmusic.module.music.service.AlbumService;
import com.example.newmusic.module.music.service.ArtistService;
import com.example.newmusic.module.music.service.LyricService;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.music.service.TagService;
import com.example.newmusic.module.music.support.MusicPinyin;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import com.example.newmusic.module.social.service.FollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SongServiceImpl extends BaseCrudService<SongMapper, Song> implements SongService {

    private static final Set<String> SORT_FIELDS = new HashSet<>(Arrays.asList(
            "id", "name", "playCount", "likeCount", "hotScore", "releaseDate",
            "publishTime", "sortWeight", "duration", "createTime", "updateTime"));

    /** 统计字段白名单（BR-10-004：普通更新接口不得修改统计字段） */
    private static final Set<String> COUNTER_COLUMNS = new HashSet<>(Arrays.asList(
            "play_count", "like_count", "collect_count", "comment_count", "share_count", "download_count"));

    /** 状态 */
    private static final int STATUS_OFFLINE = 0;
    private static final int STATUS_ONLINE = 1;

    private static final int COPYRIGHT_AUTHORIZED = 1;
    private static final int AUDIT_PASSED = 1;

    private final ArtistService artistService;
    private final AlbumService albumService;
    private final LyricService lyricService;
    private final TagService tagService;
    private final SongTagMapper songTagMapper;
    private final PlaylistSongMapper playlistSongMapper;
    private final PlaylistMapper playlistMapper;

    /** 模块 10：歌手发新歌通知关注者（FR-19-009）。 */
    private final FollowService followService;

    /** 模块 06/11：ES 模式下把增删改增量写进索引（不启用 ES 时全部是空操作）。 */
    private final SearchIndexSyncService searchIndexSyncService;

    /** 模块 03：二级缓存 / 分布式锁 / 布隆过滤器 */
    private final CacheService cacheService;
    private final DistributedLockService lockService;
    private final BloomSupport bloomSupport;
    private final long songTtlSeconds;
    private final long nullTtlSeconds;

    /** 待审歌曲 → 审核工作台（admin 侧监听，避免 music ↔ admin 构造器循环依赖）。 */
    private final ApplicationEventPublisher eventPublisher;

    public SongServiceImpl(ArtistService artistService, AlbumService albumService, LyricService lyricService,
                           TagService tagService, SongTagMapper songTagMapper,
                           PlaylistSongMapper playlistSongMapper, PlaylistMapper playlistMapper,
                           FollowService followService, SearchIndexSyncService searchIndexSyncService,
                           CacheService cacheService, DistributedLockService lockService,
                           BloomSupport bloomSupport, InfraProperties infraProperties,
                           ApplicationEventPublisher eventPublisher) {
        this.artistService = artistService;
        this.albumService = albumService;
        this.lyricService = lyricService;
        this.tagService = tagService;
        this.songTagMapper = songTagMapper;
        this.playlistSongMapper = playlistSongMapper;
        this.playlistMapper = playlistMapper;
        this.followService = followService;
        this.searchIndexSyncService = searchIndexSyncService;
        this.cacheService = cacheService;
        this.lockService = lockService;
        this.bloomSupport = bloomSupport;
        this.songTtlSeconds = infraProperties.getCache().getSongTtlSeconds();
        this.nullTtlSeconds = infraProperties.getCache().getNullTtlSeconds();
        this.eventPublisher = eventPublisher;
    }

    @Override
    public PageResult<Song> page(SongQueryReq query) {
        return doPage(query, false);
    }

    @Override
    public PageResult<Song> pagePublished(SongQueryReq query) {
        return doPage(query, true);
    }

    private PageResult<Song> doPage(SongQueryReq query, boolean publishedOnly) {
        return PageResult.of(pageQuery(query, buildWrapper(query, publishedOnly), SORT_FIELDS),
                query.isTruncated());
    }

    @Override
    public PageResult<Song> pageByPlaylist(Long playlistId, int pageNum, int pageSize) {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(pageNum);
        pageQuery.setPageSize(pageSize);
        pageQuery.normalize();

        // 先取歌单内的歌曲ID（保持 sort_order 顺序），再按 ID 批量取歌曲，避免多表 join 破坏分页语义
        List<PlaylistSong> links = playlistSongMapper.selectList(Wrappers.<PlaylistSong>query()
                .eq("playlist_id", playlistId)
                .orderByAsc("sort_order").orderByAsc("id"));
        if (CollUtil.isEmpty(links)) {
            return PageResult.empty(pageQuery.getPageNum(), pageQuery.getPageSize());
        }
        long total = links.size();
        int from = (pageQuery.getPageNum() - 1) * pageQuery.getPageSize();
        if (from >= total) {
            return PageResult.empty(pageQuery.getPageNum(), pageQuery.getPageSize());
        }
        int to = (int) Math.min(from + (long) pageQuery.getPageSize(), total);
        List<Long> pageSongIds = links.subList(from, to).stream()
                .map(PlaylistSong::getSongId).collect(Collectors.toList());

        List<Song> songs = listByIdsOrdered(pageSongIds);
        PageResult<Song> result = new PageResult<>();
        result.setList(songs);
        result.setTotal(total);
        result.setPageNum(pageQuery.getPageNum());
        result.setPageSize(pageQuery.getPageSize());
        result.setPages((total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize());
        result.setHasNext(to < total);
        result.setTruncated(pageQuery.isTruncated());
        return result;
    }

    @Override
    public List<Song> listByIdsOrdered(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<Song> songs = listByIds(ids);
        Map<Long, Song> map = songs.stream().collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
        List<Song> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Song song = map.get(id);
            if (song != null) {
                ordered.add(song);
            }
        }
        return ordered;
    }

    @Override
    public List<Long> listAllIdsForBloom() {
        return lambdaQuery().select(Song::getId).list().stream()
                .map(Song::getId)
                .collect(Collectors.toList());
    }

    @Override
    public Song detail(Long id) {
        return loadCached(requireId(id));
    }

    /**
     * 缓存取详情（FR-12-001）。
     *
     * <p>缓存里存的是 {@link Song} **实体本身**，因为本接口返回的就是实体（{@code R<Song>}）——
     * 换成任何 VO 都不可能命中。相应地有一条必须守住的边界：
     * **缓存的是事实，不是结论**。像
     * {@link #requirePlayable} 的「已上架 / 审核通过 / 版权有效 / 有标准音质」这类门禁，
     * 每次都要在取回缓存之后重新判一遍，绝不跟着缓存走——否则一次后台下架要等到 TTL 过期才生效。
     */
    private Song loadCached(Long id) {
        String key = RedisKeys.song(id);
        CacheService.Lookup<Song> lookup = cacheService.get(key, Song.class);
        if (lookup.isHit()) {
            return lookup.value();
        }
        if (lookup.isNegative()) {
            // 空值缓存命中：确认不存在，不打 DB（防穿透，BR-12-004）
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        return rebuildSongCache(key, id);
    }

    /** 缓存重建。外面套布隆过滤器 + 分布式锁，对应防穿透与防击穿 */
    private Song rebuildSongCache(String key, Long id) {
        // 防穿透第一道：布隆过滤器说「一定不存在」就不必回源。
        // 这条路径**不写空值缓存**——为每个随机 ID 写一个空值键，等于把穿透转发成了缓存写入，
        // 正是布隆过滤器要避免的事。它也拦不住重复请求（布隆本身就是那个拦截器）。
        if (!bloomSupport.mightContain(RedisKeys.BLOOM_SONG, String.valueOf(id))) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }

        // 防击穿（FR-12-006）：同一首歌只放一个线程去查库，其余在锁上等着
        return lockService.execute(RedisKeys.lockCacheSong(id), () -> {
            // 双重检查：等锁这段时间里，先拿到锁的线程很可能已经把缓存建好了
            CacheService.Lookup<Song> again = cacheService.get(key, Song.class);
            if (again.isHit()) {
                return again.value();
            }
            if (again.isNegative()) {
                throw new BizException(ResultCode.SONG_NOT_FOUND);
            }

            Song song = getById(id);
            if (song == null) {
                // 空值缓存 TTL 很短（默认 60s）：短到不会把「后来才建的数据」长期挡在外面，
                // 长到足以让同一批重复请求全部落空在缓存里
                cacheService.putNull(key, Duration.ofSeconds(nullTtlSeconds));
                throw new BizException(ResultCode.SONG_NOT_FOUND);
            }
            cacheService.put(key, song, Duration.ofSeconds(songTtlSeconds));
            return song;
        });
    }

    /**
     * 直读 DB，绕过缓存。
     *
     * <p><b>写入路径必须用它。</b>{@code update/changeStatus/audit} 都要拿现有实体做合并与
     * 乐观锁校验，而缓存里的实体可能是旧的：拿旧 version 去更新会莫名其妙地报 10008；
     * 更糟的是拿旧的归属字段去做「歌手/专辑冗余计数」的差量比较，会算错账。
     */
    private Song loadFresh(Long id) {
        Song song = getById(requireId(id));
        if (song == null) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        return song;
    }

    /**
     * 失效歌曲缓存，并在事务提交后执行「延迟双删」（FR-12-009 / 系分 D-26）。
     *
     * <p>两次删除各解决一个问题：
     * <ul>
     *   <li><b>提交后删</b>：在事务里删，DB 还没提交，并发读会读到旧值并把它写回缓存，
     *       等于白删。挂到 {@code afterCommit} 才能保证「删的时候新值已经可见」；</li>
     *   <li><b>+500ms 再删一次</b>：覆盖上面那种「删除窗口内读到旧值又回写」的漏网之鱼。
     *       它只对 Redis 与**本实例**的 L1 有效；其它实例的 L1 只能等它自己过期（系分 §9）。</li>
     * </ul>
     */
    private void evictSongCacheAfterCommit(Collection<Long> songIds) {
        if (CollUtil.isEmpty(songIds)) {
            return;
        }
        Runnable evict = () -> {
            for (Long songId : songIds) {
                if (songId == null) {
                    continue;
                }
                String key = RedisKeys.song(songId);
                cacheService.evict(key);
                cacheService.evictLater(key, Duration.ofMillis(500));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    /**
     * 新歌通知必须在事务提交后发：若先发后回滚，关注者会收到一条指向不存在歌曲的通知。
     * 通知失败只记日志，不影响发布结果（模块 10 FR-19-009）。
     */
    private void notifyNewSongAfterCommit(Song song) {
        Runnable notify = () -> {
            try {
                followService.notifyNewSong(song);
            } catch (Exception e) {
                log.warn("新歌通知失败 songId={} cause={}", song.getId(), e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
        } else {
            notify.run();
        }
    }

    /**
     * 索引同步放在提交后：事务回滚时不能把一条不存在的歌写进 ES
     * （ES 没有事务，写进去就只能等下次重建才对得上）。
     *
     * <p>索引失败不影响业务成功——它只记日志，搜索侧下次重建会自愈。
     */
    private void syncSearchIndexAfterCommit(Collection<Long> songIds) {
        if (CollUtil.isEmpty(songIds)) {
            return;
        }
        List<Long> ids = new ArrayList<>(songIds);
        SearchIndexSyncService.afterCommit(() -> {
            for (Long songId : ids) {
                searchIndexSyncService.syncSong(songId);
            }
        });
    }

    private void removeSearchIndexAfterCommit(Collection<Long> songIds) {
        if (CollUtil.isEmpty(songIds)) {
            return;
        }
        List<Long> ids = new ArrayList<>(songIds);
        SearchIndexSyncService.afterCommit(() -> {
            for (Long songId : ids) {
                searchIndexSyncService.deleteSong(songId);
            }
        });
    }

    @Override
    public Song requirePlayable(Long id) {
        Song song = detail(id);
        if (!Objects.equals(song.getStatus(), STATUS_ONLINE)) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        if (Objects.equals(song.getAuditStatus(), 0)) {
            throw new BizException(ResultCode.SONG_AUDITING);
        }
        if (!Objects.equals(song.getAuditStatus(), AUDIT_PASSED)) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        if (!isCopyrightValid(song)) {
            throw new BizException(ResultCode.SONG_NO_COPYRIGHT);
        }
        if (StrUtil.isBlank(song.getAudioStandard())) {
            throw new BizException(ResultCode.AUDIO_QUALITY_UNAVAILABLE, "该歌曲暂无可用音质");
        }
        return song;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SongSaveReq req) {
        Song entity = new Song();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        fillRedundant(entity, req);

        entity.setPlayCount(0L);
        entity.setLikeCount(0L);
        entity.setCollectCount(0L);
        entity.setCommentCount(0L);
        entity.setShareCount(0L);
        entity.setDownloadCount(0L);
        entity.setHotScore(0);
        entity.setHasTranslation(0);
        entity.setSource(entity.getSource() == null ? 1 : entity.getSource());
        entity.setSortWeight(entity.getSortWeight() == null ? 0 : entity.getSortWeight());
        entity.setStatus(entity.getStatus() == null ? STATUS_OFFLINE : entity.getStatus());
        entity.setAuditStatus(entity.getAuditStatus() == null ? 0 : entity.getAuditStatus());
        entity.setCopyrightStatus(entity.getCopyrightStatus() == null ? 0 : entity.getCopyrightStatus());
        entity.setLanguage(StrUtil.blankToDefault(entity.getLanguage(), "国语"));
        // 直播等场景可能直接带标签创建
        if (Objects.equals(entity.getStatus(), STATUS_ONLINE)) {
            checkPublishable(entity);
            entity.setPublishTime(LocalDateTime.now());
        }

        save(entity);
        // 冗余计数：歌手的歌曲数
        artistService.changeSongCount(entity.getArtistId(), 1);
        if (entity.getAlbumId() != null) {
            albumService.changeSongCount(entity.getAlbumId(), 1);
        }
        if (CollUtil.isNotEmpty(req.getTagIds())) {
            tagService.bindSongTags(entity.getId(), req.getTagIds());
        }
        // 新歌要进布隆过滤器，否则它的详情请求会被「一定不存在」挡在缓存重建之前。
        // 放在这里而不是提交后：万一事务回滚，也只是过滤器里多一个假阳性（多回源一次库），
        // 不会误伤任何真实数据——布隆过滤器的误判方向决定了它可以这样用
        bloomSupport.add(RedisKeys.BLOOM_SONG, String.valueOf(entity.getId()));
        if (Objects.equals(entity.getStatus(), STATUS_ONLINE)
                && Integer.valueOf(AUDIT_PASSED).equals(entity.getAuditStatus())) {
            notifyNewSongAfterCommit(entity);
        }
        // 导入/新增时随行带歌词：音频文件本身没有「平台歌词记录」，不带就必然是「有歌无词」
        saveLyricIfPresent(entity.getId(), req);
        if (Objects.equals(entity.getAuditStatus(), 0)) {
            publishAuditSubmitted(entity);
        }
        syncSearchIndexAfterCommit(Collections.singletonList(entity.getId()));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SongSaveReq req) {
        requireVersion(req.getVersion());
        Song exists = loadFresh(id);
        boolean wasOnline = Objects.equals(exists.getStatus(), STATUS_ONLINE);

        Song entity = new Song();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        // 统计字段一律不允许通过更新接口写入（BR-10-004）
        entity.setPlayCount(null);
        entity.setLikeCount(null);
        entity.setCollectCount(null);
        entity.setCommentCount(null);
        entity.setShareCount(null);
        entity.setDownloadCount(null);
        entity.setHotScore(null);
        entity.setPublishTime(null);
        fillRedundant(entity, req);

        Integer targetStatus = entity.getStatus() == null ? exists.getStatus() : entity.getStatus();
        if (Objects.equals(targetStatus, STATUS_ONLINE)) {
            Song merged = new Song();
            BeanUtils.copyProperties(exists, merged);
            BeanUtils.copyProperties(entity, merged, "id", "version", "playCount", "likeCount", "collectCount",
                    "commentCount", "shareCount", "downloadCount", "hotScore", "publishTime");
            checkPublishable(merged);
            if (!Objects.equals(exists.getStatus(), STATUS_ONLINE)) {
                // 首次上架记录上架时间
                entity.setPublishTime(LocalDateTime.now());
            }
        }
        updateWithVersion(entity);

        if (req.getTagIds() != null) {
            tagService.bindSongTags(exists.getId(), req.getTagIds());
        }
        // 归属变化时同步冗余计数
        if (!Objects.equals(exists.getArtistId(), entity.getArtistId())) {
            artistService.changeSongCount(exists.getArtistId(), -1);
            artistService.changeSongCount(entity.getArtistId(), 1);
        }
        if (!Objects.equals(exists.getAlbumId(), entity.getAlbumId())) {
            albumService.changeSongCount(exists.getAlbumId(), -1);
            albumService.changeSongCount(entity.getAlbumId(), 1);
        }

        saveLyricIfPresent(exists.getId(), req);
        evictSongCacheAfterCommit(Collections.singletonList(id));
        syncSearchIndexAfterCommit(Collections.singletonList(id));
        Integer auditStatus = entity.getAuditStatus() == null
                ? exists.getAuditStatus() : entity.getAuditStatus();
        if (Objects.equals(auditStatus, 0)) {
            // 编辑待审歌曲（例如补歌词/换音源）也确保审核台上有一条可处理的任务。
            // 已是待审且任务已存在时由监听器去重，不会堆出重复任务
            publishAuditSubmitted(entity.getId(), entity.getName() == null ? exists.getName() : entity.getName(),
                    entity.getArtistName() == null ? exists.getArtistName() : entity.getArtistName(),
                    entity.getAudioStandard() == null ? exists.getAudioStandard() : entity.getAudioStandard());
        }
        if (!wasOnline && Objects.equals(targetStatus, STATUS_ONLINE)
                && Integer.valueOf(AUDIT_PASSED).equals(auditStatus)) {
            notifyNewSongAfterCommit(exists);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != STATUS_OFFLINE && status != STATUS_ONLINE)) {
            throw new BizException(ResultCode.PARAM_INVALID, "status 仅支持 0(下架) / 1(上架)");
        }
        Song exists = loadFresh(id);
        if (Objects.equals(exists.getStatus(), status)) {
            return; // 幂等
        }
        Song update = new Song();
        update.setId(exists.getId());
        update.setVersion(exists.getVersion());
        update.setStatus(status);
        if (status == STATUS_ONLINE) {
            checkPublishable(exists);
            update.setPublishTime(LocalDateTime.now());
        }
        updateWithVersion(update);
        // 上下架必须立刻让缓存失效：这是「缓存事实不缓存结论」里最容易出事的一条——
        // 下架后如果详情还命中缓存，缓存里 status=1，requirePlayable 的门禁会把它拦下来，
        // 看起来没问题；但 detail()（有 read 权限的后台）会看到下架前的数据
        evictSongCacheAfterCommit(Collections.singletonList(id));
        syncSearchIndexAfterCommit(Collections.singletonList(id));
        if (status == STATUS_ONLINE && Integer.valueOf(AUDIT_PASSED).equals(exists.getAuditStatus())) {
            notifyNewSongAfterCommit(exists);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, Integer auditStatus) {
        if (auditStatus == null || auditStatus < 0 || auditStatus > 2) {
            throw new BizException(ResultCode.PARAM_INVALID, "auditStatus 仅支持 0待审/1通过/2拒绝");
        }
        Song exists = loadFresh(id);
        Song update = new Song();
        update.setId(exists.getId());
        update.setVersion(exists.getVersion());
        update.setAuditStatus(auditStatus);
        if (auditStatus == 2 && Objects.equals(exists.getStatus(), STATUS_ONLINE)) {
            // 审核拒绝需同步下架，避免违规歌曲继续可播
            update.setStatus(STATUS_OFFLINE);
        }
        updateWithVersion(update);
        evictSongCacheAfterCommit(Collections.singletonList(id));
        syncSearchIndexAfterCommit(Collections.singletonList(id));
        if (auditStatus == AUDIT_PASSED && Objects.equals(exists.getStatus(), STATUS_ONLINE)) {
            notifyNewSongAfterCommit(exists);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Song exists = loadFresh(id);
        removeSafe(exists.getId());
        cascadeAfterRemove(Collections.singletonList(exists));
        evictSongCacheAfterCommit(Collections.singletonList(id));
        removeSearchIndexAfterCommit(Collections.singletonList(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new BizException(ResultCode.BATCH_SIZE_EXCEEDED);
        }
        // 先取出实体（删除后逻辑删除过滤将查不到），供级联清理与计数回退使用
        List<Song> songs = listByIds(distinct);
        batchRemoveSafe(distinct);
        cascadeAfterRemove(songs);
        evictSongCacheAfterCommit(distinct);
        removeSearchIndexAfterCommit(distinct);
    }

    @Override
    public void incrementCounter(Long songId, String column, long delta) {
        if (!COUNTER_COLUMNS.contains(column)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持统计字段：" + column);
        }
        baseMapper.incrementCounter(songId, column, delta);
    }

    /**
     * 歌曲删除后的级联清理（BR-10-024）。
     *
     * <p>必须在**逻辑删除之前**取出实体（{@code deleted=1} 后查询会被自动过滤），
     * 因此由 {@link #delete}/{@link #batchDelete} 显式调用，而非复用基类的
     * {@code afterRemove} 钩子。
     */
    private void cascadeAfterRemove(List<Song> songs) {
        if (CollUtil.isEmpty(songs)) {
            return;
        }
        List<Long> songIds = songs.stream().map(Song::getId).collect(Collectors.toList());

        // 1) 歌曲标签：关联物理删除 + 标签 use_count 回退
        List<SongTag> songTags = songTagMapper.selectList(Wrappers.<SongTag>query().in("song_id", songIds));
        if (CollUtil.isNotEmpty(songTags)) {
            songTagMapper.delete(Wrappers.<SongTag>query().in("song_id", songIds));
            Map<Long, Long> perTag = songTags.stream()
                    .collect(Collectors.groupingBy(SongTag::getTagId, Collectors.counting()));
            perTag.forEach((tagId, count) -> tagService.changeUseCount(tagId, -count.intValue()));
        }

        // 2) 歌单关联：物理删除 + 歌单 song_count 回退
        List<PlaylistSong> links = playlistSongMapper.selectList(Wrappers.<PlaylistSong>query().in("song_id", songIds));
        if (CollUtil.isNotEmpty(links)) {
            playlistSongMapper.delete(Wrappers.<PlaylistSong>query().in("song_id", songIds));
            Map<Long, Long> perPlaylist = links.stream()
                    .collect(Collectors.groupingBy(PlaylistSong::getPlaylistId, Collectors.counting()));
            perPlaylist.forEach((playlistId, count) ->
                    playlistMapper.updateSongCount(playlistId, -count.intValue()));
        }

        // 3) 歌词：随歌曲逻辑删除
        for (Long songId : songIds) {
            lyricService.removeBySongId(songId);
        }

        // 4) 冗余计数回退
        for (Song song : songs) {
            artistService.changeSongCount(song.getArtistId(), -1);
            albumService.changeSongCount(song.getAlbumId(), -1);
        }

        // 缓存失效已在 delete / batchDelete 里以「提交后 + 延迟双删」的方式登记
        // （见 evictSongCacheAfterCommit）。这里不做，因为在事务内删除会被并发读的旧值回写覆盖。
        // TODO(模块06)：删除 ES 文档
    }

    private QueryWrapper<Song> buildWrapper(SongQueryReq query, boolean publishedOnly) {
        QueryWrapper<Song> wrapper = Wrappers.query();
        wrapper.eq(query.getArtistId() != null, "artist_id", query.getArtistId());
        wrapper.eq(query.getAlbumId() != null, "album_id", query.getAlbumId());
        if (publishedOnly) {
            // 前台强制上架 + 审核通过（BR-04-001）；忽略客户端传入的 status/auditStatus，
            // 否则会被拼成 status=0 AND status=1 之类的永假条件
            wrapper.eq("status", STATUS_ONLINE).eq("audit_status", AUDIT_PASSED);
        } else {
            wrapper.eq(query.getStatus() != null, "status", query.getStatus());
            wrapper.eq(query.getAuditStatus() != null, "audit_status", query.getAuditStatus());
        }
        wrapper.eq(StrUtil.isNotBlank(query.getGenre()), "genre", query.getGenre());
        wrapper.eq(StrUtil.isNotBlank(query.getLanguage()), "language", query.getLanguage());
        wrapper.ge(query.getBpmMin() != null, "bpm", query.getBpmMin());
        wrapper.le(query.getBpmMax() != null, "bpm", query.getBpmMax());
        if (CollUtil.isNotEmpty(query.getExcludeIds())) {
            // 限量，避免 IN 列表过大（EX-10-001-2）
            List<Long> excludes = query.getExcludeIds().stream().filter(Objects::nonNull).limit(200)
                    .collect(Collectors.toList());
            if (CollUtil.isNotEmpty(excludes)) {
                wrapper.notIn("id", excludes);
            }
        }
        if (query.getTagId() != null) {
            // 值已由 Long 反序列化保证安全，不拼接字符串入参
            wrapper.inSql("id", "SELECT song_id FROM t_song_tag WHERE tag_id = " + query.getTagId());
        }
        if (query.getPlaylistId() != null) {
            wrapper.inSql("id", "SELECT song_id FROM t_playlist_song WHERE playlist_id = " + query.getPlaylistId());
        }
        if (query.hasKeyword()) {
            String kw = LikeUtil.escape(query.getKeyword());
            wrapper.and(w -> w.like("name", kw).or().like("artist_name", kw).or().like("album_name", kw));
        }
        return wrapper;
    }

    /** 填充冗余字段（artist_name / album_name / cover）并校验歌手/专辑存在。 */
    private void fillRedundant(Song entity, SongSaveReq req) {
        Artist artist = artistService.require(req.getArtistId());
        entity.setArtistName(artist.getName());
        if (req.getAlbumId() != null) {
            Album album = albumService.require(req.getAlbumId());
            entity.setAlbumName(album.getName());
            if (StrUtil.isBlank(req.getCover())) {
                entity.setCover(album.getCover());
            }
        } else {
            entity.setAlbumId(null);
            entity.setAlbumName(null);
        }
        // 拼音检索列（模块 06 D-35）。放在这里而不是 create/update 各自调一次：
        // 冗余歌手名是在上面刚定下来的，拼音必须跟着**最终**的歌手名算，
        // 否则歌手改名后歌曲的 artist_name_pinyin 会留在旧值上
        MusicPinyin.fill(entity);
    }

    /**
     * 随歌曲写入歌词正文（可选字段）；调用方没带歌词时直接跳过，不产生空的歌词记录。
     *
     * <p>只在正文非空时调用 {@code upsertContent}：编辑歌曲时「没传 lyric」和「传空串」
     * 语义不同——前者代表这次不改歌词（保持原样），后者代表清空。当前接口没有「清空歌词」
     * 的需求，所以空串也按「不改」处理，避免运营编辑价格/封面时误删歌词。
     */
    private void saveLyricIfPresent(Long songId, SongSaveReq req) {
        if (songId == null || req == null || StrUtil.isBlank(req.getLyric())) {
            return;
        }
        lyricService.upsertContent(songId, req.getLyric(), req.getLyricContentType());
    }

    private void publishAuditSubmitted(Song song) {
        publishAuditSubmitted(song.getId(), song.getName(), song.getArtistName(), song.getAudioStandard());
    }

    /**
     * 发布「歌曲进入待审」事件，由 admin 侧的 {@code SongAuditTaskService} 建审核任务。
     *
     * <p>事件里的歌名/歌手/音源只是**给日志和快照用的冗余值**，监听器会重新按 songId 读库，
     * 因此编辑场景可以安全地传「本次请求值优先、否则用旧值」的合并结果。
     *
     * <p>发布失败只记 WARN：歌曲已经保存成功，不能因为审核台不可用把歌曲创建回滚掉；
     * 下次启动的回填会补上这条任务。
     */
    private void publishAuditSubmitted(Long songId, String name, String artistName, String audioStandard) {
        if (songId == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new SongSubmittedForAuditEvent(songId, name, artistName, audioStandard));
        } catch (Exception e) {
            log.warn("发布歌曲待审事件失败 songId={} cause={}", songId, e.toString());
        }
    }

    /** BR-04-001 / BR-04-002：上架前置条件。 */
    private void checkPublishable(Song song) {
        if (!Objects.equals(song.getAuditStatus(), AUDIT_PASSED)
                || !isCopyrightValid(song)
                || StrUtil.isBlank(song.getAudioStandard())) {
            throw new BizException(ResultCode.SONG_NOT_PUBLISHABLE);
        }
    }

    private boolean isCopyrightValid(Song song) {
        if (!Objects.equals(song.getCopyrightStatus(), COPYRIGHT_AUTHORIZED)) {
            return false;
        }
        return song.getCopyrightExpire() == null || !song.getCopyrightExpire().isBefore(java.time.LocalDate.now());
    }
}
