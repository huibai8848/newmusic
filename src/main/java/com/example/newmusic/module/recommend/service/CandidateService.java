package com.example.newmusic.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.entity.SongTag;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.mapper.SongTagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐候选池。
 *
 * <p>只保留「可播放」歌曲：上架 + 审核通过 + 已授权 + 版权未过期 + 有标准音源
 * （BR-17-012 / BR-17-032）。过滤放在候选池做一次，后续所有召回与排序都不必重复判断。
 *
 * <p>候选与标签在进程内缓存 60 秒。曲库规模上万时应改成 Redis 或离线快照；
 * 当前演示曲库只有几十首，每次推荐都全表扫描反而更简单。
 */
@Slf4j
@Service
public class CandidateService {

    private static final long CACHE_MILLIS = 60_000L;

    private final SongMapper songMapper;
    private final SongTagMapper songTagMapper;
    private final ArtistMapper artistMapper;

    private volatile List<Song> cachedSongs = Collections.emptyList();
    private volatile long songsCachedAt;
    private volatile Map<Long, Set<Long>> cachedTags = Collections.emptyMap();
    private volatile long tagsCachedAt;

    public CandidateService(SongMapper songMapper, SongTagMapper songTagMapper, ArtistMapper artistMapper) {
        this.songMapper = songMapper;
        this.songTagMapper = songTagMapper;
        this.artistMapper = artistMapper;
    }

    /** 全部可播放歌曲，按热度分降序。 */
    public List<Song> playableSongs() {
        long now = System.currentTimeMillis();
        if (now - songsCachedAt < CACHE_MILLIS && !cachedSongs.isEmpty()) {
            return cachedSongs;
        }
        List<Song> raw = songMapper.selectList(Wrappers.<Song>query()
                .eq("status", 1)
                .eq("audit_status", 1));
        LocalDate today = LocalDate.now();
        List<Song> playable = raw.stream()
                .filter(this::isPlayable)
                .filter(song -> song.getCopyrightExpire() == null || !song.getCopyrightExpire().isBefore(today))
                .sorted(Comparator
                        .comparingInt((Song s) -> s.getHotScore() == null ? 0 : s.getHotScore())
                        .reversed()
                        .thenComparing(s -> s.getPlayCount() == null ? 0L : s.getPlayCount(), Comparator.reverseOrder()))
                .collect(Collectors.toList());
        cachedSongs = Collections.unmodifiableList(playable);
        songsCachedAt = now;
        return cachedSongs;
    }

    public boolean isPlayable(Song song) {
        return song != null
                && Integer.valueOf(1).equals(song.getStatus())
                && Integer.valueOf(1).equals(song.getAuditStatus())
                && Integer.valueOf(1).equals(song.getCopyrightStatus())
                && song.getAudioStandard() != null
                && !song.getAudioStandard().trim().isEmpty();
    }

    /** 按 ID 取可播放歌曲，保持入参顺序；不可播放的会被丢弃。 */
    public List<Song> playableByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Song> byId = playableSongs().stream()
                .collect(Collectors.toMap(Song::getId, s -> s, (a, b) -> a));
        List<Song> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Song song = byId.get(id);
            if (song != null) {
                result.add(song);
            }
        }
        return result;
    }

    public Song findPlayable(Long songId) {
        if (songId == null) {
            return null;
        }
        for (Song song : playableSongs()) {
            if (songId.equals(song.getId())) {
                return song;
            }
        }
        return null;
    }

    /** songId → tagId 集合，用于标签 Jaccard 与画像标签权重。 */
    public Map<Long, Set<Long>> songTags() {
        long now = System.currentTimeMillis();
        if (now - tagsCachedAt < CACHE_MILLIS && !cachedTags.isEmpty()) {
            return cachedTags;
        }
        List<SongTag> rows = songTagMapper.selectList(Wrappers.<SongTag>query());
        Map<Long, Set<Long>> map = new HashMap<>();
        for (SongTag row : rows) {
            if (row.getSongId() != null && row.getTagId() != null) {
                map.computeIfAbsent(row.getSongId(), k -> new HashSet<>()).add(row.getTagId());
            }
        }
        cachedTags = map;
        tagsCachedAt = now;
        return cachedTags;
    }

    public Set<Long> tagsOf(Long songId) {
        return songTags().getOrDefault(songId, Collections.emptySet());
    }

    /** songId → Song，调用方需要批量回填歌曲详情时用。 */
    public Map<Long, Song> playableMap() {
        Map<Long, Song> map = new LinkedHashMap<>();
        for (Song song : playableSongs()) {
            map.put(song.getId(), song);
        }
        return map;
    }

    public List<Song> hotSongs(int limit) {
        List<Song> all = playableSongs();
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    /** 歌手信息（近听模块要头像）。只查需要的 ID。 */
    public Map<Long, Artist> artists(Collection<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Artist> artists = artistMapper.selectBatchIds(new HashSet<>(artistIds));
        Map<Long, Artist> map = new HashMap<>();
        for (Artist artist : artists) {
            map.put(artist.getId(), artist);
        }
        return map;
    }

    /** 曲库变化后主动失效（运营上下架、导入新歌时调用；当前主要给测试/任务用）。 */
    public void invalidate() {
        songsCachedAt = 0L;
        tagsCachedAt = 0L;
    }
}
