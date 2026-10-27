package com.example.newmusic.module.search.engine.es;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.support.EsClient;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把内容变更增量写进 ES（模块 06 / 模块 11）。
 *
 * <p>只有全量重建是不够的：后台新增/审核通过一首歌之后，如果不写索引，
 * 用户在 ES 模式下就是搜不到它——而且要等到下一次重建才出现。这类「刚发布搜不到」
 * 的问题在接入真实 ES 后一定会被提出来，所以写路径直接做增量。
 *
 * <p><b>几条硬约束：</b>
 * <ul>
 *   <li><b>永不抛异常</b>：调用方是业务写事务的 afterCommit 钩子，检索索引失败不能让人
 *       以为「歌曲没建成功」，只记日志；</li>
 *   <li><b>只写通过筛选条件的文档</b>：下架/未过审的歌曲要从索引移除，
 *       否则搜索会把一首已下架的歌推给用户；</li>
 *   <li><b>{@code refresh=true}</b>：后台是低频写，让下一次搜索立刻能看到；
 *       用户侧高频写（播放量等）不经过这里；</li>
 *   <li>ES 未启用/不可达/别名不存在时**静默跳过**，搜索由 MySQL 引擎兜底。</li>
 * </ul>
 */
@Slf4j
@Service
public class SearchIndexSyncService {

    /** 与 {@code EsReindexService} 的口径一致：上架 + 审核通过才进索引 */
    private static final int ONLINE = 1;
    private static final int AUDIT_PASSED = 1;

    /** 别名存在性检查的缓存时长：避免每次写都多一次 HEAD */
    private static final long ALIAS_CHECK_MILLIS = 60_000L;

    private final EsClient esClient;
    private final DictProperties.Es esProperties;
    private final EsDocFactory docFactory;
    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final AlbumMapper albumMapper;
    private final PlaylistMapper playlistMapper;
    private final LyricMapper lyricMapper;

    private volatile boolean aliasReady = false;
    private volatile long lastAliasCheck = 0L;

    public SearchIndexSyncService(EsClient esClient, DictProperties dictProperties, EsDocFactory docFactory,
                                  SongMapper songMapper, ArtistMapper artistMapper, AlbumMapper albumMapper,
                                  PlaylistMapper playlistMapper, LyricMapper lyricMapper) {
        this.esClient = esClient;
        this.esProperties = dictProperties.getEs();
        this.docFactory = docFactory;
        this.songMapper = songMapper;
        this.artistMapper = artistMapper;
        this.albumMapper = albumMapper;
        this.playlistMapper = playlistMapper;
        this.lyricMapper = lyricMapper;
    }

    /**
     * 事务提交后执行；没有事务（单测、脚本直调）就立即执行。
     *
     * <p>写索引必须等提交：ES 没有事务，回滚后索引里多出来的文档只能等下次重建才消失。
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ==================== 歌曲 ====================

    /** 新建/修改/上下架/审核后调用：满足条件就 upsert，不满足就从索引删掉。 */
    public void syncSong(Long songId) {
        if (!ready() || songId == null) {
            return;
        }
        try {
            Song song = songMapper.selectById(songId);
            if (!searchable(song)) {
                deleteDoc("SONG_" + songId);
                return;
            }
            putDoc("SONG_" + songId, docFactory.song(song, lyricOf(songId)));
        } catch (RuntimeException e) {
            log.warn("同步歌曲索引失败 songId={} cause={}", songId, e.toString());
        }
    }

    public void deleteSong(Long songId) {
        if (!ready() || songId == null) {
            return;
        }
        try {
            deleteDoc("SONG_" + songId);
        } catch (RuntimeException e) {
            log.warn("删除歌曲索引失败 songId={} cause={}", songId, e.toString());
        }
    }

    // ==================== 歌手 / 专辑 / 歌单 ====================

    public void syncArtist(Long artistId) {
        if (!ready() || artistId == null) {
            return;
        }
        try {
            Artist artist = artistMapper.selectById(artistId);
            if (artist == null || !Integer.valueOf(ONLINE).equals(artist.getStatus())) {
                deleteDoc("ARTIST_" + artistId);
                return;
            }
            putDoc("ARTIST_" + artistId, docFactory.artist(artist));
        } catch (RuntimeException e) {
            log.warn("同步歌手索引失败 artistId={} cause={}", artistId, e.toString());
        }
    }

    public void syncAlbum(Long albumId) {
        if (!ready() || albumId == null) {
            return;
        }
        try {
            Album album = albumMapper.selectById(albumId);
            if (album == null || !Integer.valueOf(ONLINE).equals(album.getStatus())) {
                deleteDoc("ALBUM_" + albumId);
                return;
            }
            putDoc("ALBUM_" + albumId, docFactory.album(album));
        } catch (RuntimeException e) {
            log.warn("同步专辑索引失败 albumId={} cause={}", albumId, e.toString());
        }
    }

    public void syncPlaylist(Long playlistId) {
        if (!ready() || playlistId == null) {
            return;
        }
        try {
            Playlist playlist = playlistMapper.selectById(playlistId);
            if (playlist == null || !Integer.valueOf(ONLINE).equals(playlist.getStatus())
                    || !Integer.valueOf(ONLINE).equals(playlist.getIsPublic())) {
                deleteDoc("PLAYLIST_" + playlistId);
                return;
            }
            putDoc("PLAYLIST_" + playlistId, docFactory.playlist(playlist));
        } catch (RuntimeException e) {
            log.warn("同步歌单索引失败 playlistId={} cause={}", playlistId, e.toString());
        }
    }

    // ==================== 内部 ====================

    private boolean searchable(Song song) {
        return song != null
                && Integer.valueOf(ONLINE).equals(song.getStatus())
                && Integer.valueOf(AUDIT_PASSED).equals(song.getAuditStatus());
    }

    private String lyricOf(Long songId) {
        Lyric lyric = lyricMapper.selectOne(new LambdaQueryWrapper<Lyric>()
                .eq(Lyric::getSongId, songId)
                .eq(Lyric::getAuditStatus, AUDIT_PASSED)
                .last("LIMIT 1"));
        return lyric == null ? null : lyric.getContent();
    }

    /**
     * ES 能不能用 + 别名在不在。
     *
     * <p>别名检查按 60 秒缓存：首次启动时 {@code EsIndexInitializer} 可能还没建好索引，
     * 这期间的写操作会被跳过（下次重建会补上），没必要为它每次写都发一个 HEAD。
     */
    private boolean ready() {
        if (!esProperties.isEnabled() || !esClient.isAvailable()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastAliasCheck < ALIAS_CHECK_MILLIS) {
            return aliasReady;
        }
        lastAliasCheck = now;
        try {
            aliasReady = esClient.indexExists(esProperties.getIndexAlias());
        } catch (RuntimeException e) {
            aliasReady = false;
            log.debug("检查 ES 别名失败：{}", e.toString());
        }
        return aliasReady;
    }

    private void putDoc(String docId, ObjectNode doc) {
        // refresh=true：让紧接着的搜索能立刻看到这次变更（后台写是低频操作）
        esClient.exchange(HttpMethod.PUT,
                "/" + esProperties.getIndexAlias() + "/_doc/" + docId + "?refresh=true", doc);
    }

    private void deleteDoc(String docId) {
        try {
            esClient.exchange(HttpMethod.DELETE,
                    "/" + esProperties.getIndexAlias() + "/_doc/" + docId + "?refresh=true", null);
        } catch (RuntimeException e) {
            // 文档本来就不在索引里（404）是常态：删一首从未索引过的歌不该有告警
            log.debug("删除 ES 文档 {} 未成功（可能本就不存在）：{}", docId, e.toString());
        }
    }
}
