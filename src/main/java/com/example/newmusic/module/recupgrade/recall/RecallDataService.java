package com.example.newmusic.module.recupgrade.recall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.PlaylistSong;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.PlaylistSongMapper;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.recommend.entity.SongSimilar;
import com.example.newmusic.module.recommend.mapper.SongSimilarMapper;
import com.example.newmusic.module.recommend.service.CandidateService;
import com.example.newmusic.module.recommend.service.ScoringService;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.social.entity.ArtistFollow;
import com.example.newmusic.module.social.entity.Collect;
import com.example.newmusic.module.social.entity.Follow;
import com.example.newmusic.module.social.mapper.ArtistFollowMapper;
import com.example.newmusic.module.social.mapper.CollectMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 召回阶段的只读数据访问层（FR-24-001）。
 *
 * <p>把「候选池、标签、相似关系、关注、收藏、好友、播放历史」收在一处，好处是
 * 15 个召回源都只关心「取哪些 ID」，不必各自拼 SQL；坏处是这里一旦慢，所有源一起慢，
 * 因此：曲库与歌单在进程内缓存，历史类查询限定时间窗与条数，任何异常都返回空集合。
 *
 * <p><b>本机替代方案</b>：没有 Milvus，{@code vector} 源用画像加权打分代替向量近邻；
 * 没有地区字段，{@code local} 源退化为「本地主题歌单」。两处都保留了源的名字与契约，
 * 未来接入真实服务只需替换本类中的实现。
 */
@Slf4j
@Service
public class RecallDataService {

    private static final long PLAYLIST_CACHE_MILLIS = 300_000L;

    private final CandidateService candidateService;
    private final ScoringService scoringService;
    private final PlayHistoryMapper playHistoryMapper;
    private final SongSimilarMapper songSimilarMapper;
    private final ArtistFollowMapper artistFollowMapper;
    private final FollowMapper followMapper;
    private final CollectMapper collectMapper;
    private final PlaylistMapper playlistMapper;
    private final PlaylistSongMapper playlistSongMapper;
    private final RecUpgradeProperties properties;

    private volatile List<Long> editorSongIds = Collections.emptyList();
    private volatile long editorCachedAt;

    public RecallDataService(CandidateService candidateService, ScoringService scoringService,
                             PlayHistoryMapper playHistoryMapper, SongSimilarMapper songSimilarMapper,
                             ArtistFollowMapper artistFollowMapper, FollowMapper followMapper,
                             CollectMapper collectMapper, PlaylistMapper playlistMapper,
                             PlaylistSongMapper playlistSongMapper, RecUpgradeProperties properties) {
        this.candidateService = candidateService;
        this.scoringService = scoringService;
        this.playHistoryMapper = playHistoryMapper;
        this.songSimilarMapper = songSimilarMapper;
        this.artistFollowMapper = artistFollowMapper;
        this.followMapper = followMapper;
        this.collectMapper = collectMapper;
        this.playlistMapper = playlistMapper;
        this.playlistSongMapper = playlistSongMapper;
        this.properties = properties;
    }

    public List<Song> playableSongs() {
        return candidateService.playableSongs();
    }

    public Map<Long, Song> playableMap() {
        return candidateService.playableMap();
    }

    public Song findPlayable(Long songId) {
        return candidateService.findPlayable(songId);
    }

    public Set<Long> tagsOf(Long songId) {
        return candidateService.tagsOf(songId);
    }

    /** 用户画像。游客返回空画像，调用方靠 {@link RecallContext#hasUser()} 判断。 */
    public ScoringService.TasteProfile profile(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return scoringService.buildProfile(userId);
        } catch (RuntimeException e) {
            log.warn("推荐画像构建失败 userId={} cause={}", userId, e.toString());
            return null;
        }
    }

    /** 协同召回：与我听过的歌共同出现的歌（cf_user）。 */
    public List<Long> coOccurrenceSongs(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(90);
            return playHistoryMapper.selectCoOccurrences(userId, since, limit).stream()
                    .map(PlayHistoryMapper.CoOccurrenceRow::getSongId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            log.warn("cf_user 召回查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyList();
        }
    }

    /** 物品相似召回：给定歌曲的相似歌曲（cf_item / vector_song）。 */
    public List<Long> similarSongs(Long songId, int limit) {
        if (songId == null) {
            return Collections.emptyList();
        }
        try {
            List<SongSimilar> rows = songSimilarMapper.selectList(Wrappers.<SongSimilar>lambdaQuery()
                    .eq(SongSimilar::getSongId, songId)
                    .orderByAsc(SongSimilar::getRankNo)
                    .last("LIMIT " + Math.max(1, limit)));
            return rows.stream().map(SongSimilar::getSimilarSongId)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        } catch (RuntimeException e) {
            log.warn("相似歌曲查询失败 songId={} cause={}", songId, e.toString());
            return Collections.emptyList();
        }
    }

    public Set<Long> followedArtistIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        try {
            return artistFollowMapper.selectList(Wrappers.<ArtistFollow>lambdaQuery()
                            .eq(ArtistFollow::getUserId, userId))
                    .stream().map(ArtistFollow::getArtistId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException e) {
            log.warn("关注歌手查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptySet();
        }
    }

    public Set<Long> friendIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        try {
            return followMapper.selectList(Wrappers.<Follow>lambdaQuery()
                            .eq(Follow::getFollowerId, userId))
                    .stream().map(Follow::getFollowingId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException e) {
            log.warn("好友查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptySet();
        }
    }

    /** 好友最近 30 天在听的歌，按人数/次数排序。 */
    public List<Long> friendListeningSongs(Long userId, int limit) {
        Set<Long> friends = friendIds(userId);
        if (friends.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(30);
            return playHistoryMapper.selectPopularAmongUsers(friends, since, limit).stream()
                    .map(PlayHistoryMapper.CoOccurrenceRow::getSongId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            log.warn("好友召回查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyList();
        }
    }

    /** 用户收藏的歌单里的歌（playlist_match）。 */
    public List<Long> collectedPlaylistSongs(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            List<Long> playlistIds = collectMapper.selectList(Wrappers.<Collect>lambdaQuery()
                            .eq(Collect::getUserId, userId)
                            .eq(Collect::getTargetType, "PLAYLIST"))
                    .stream().map(Collect::getTargetId).filter(java.util.Objects::nonNull)
                    .distinct().limit(20).collect(Collectors.toList());
            if (playlistIds.isEmpty()) {
                return Collections.emptyList();
            }
            return songsOfPlaylists(playlistIds, limit);
        } catch (RuntimeException e) {
            log.warn("歌单召回查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyList();
        }
    }

    /** 编辑精选：type=2 的歌单（运营人工维护，weight 2.0）。 */
    public List<Long> editorSongs(int limit) {
        long now = System.currentTimeMillis();
        if (now - editorCachedAt < PLAYLIST_CACHE_MILLIS && !editorSongIds.isEmpty()) {
            return editorSongIds.size() <= limit ? editorSongIds
                    : new ArrayList<>(editorSongIds.subList(0, Math.max(0, limit)));
        }
        try {
            List<Long> playlistIds = playlistMapper.selectList(Wrappers.<Playlist>lambdaQuery()
                            .eq(Playlist::getPlaylistType, Playlist.TYPE_EDITOR)
                            .eq(Playlist::getStatus, 1)
                            .eq(Playlist::getAuditStatus, 1)
                            .orderByDesc(Playlist::getSortWeight)
                            .last("LIMIT 20"))
                    .stream().map(Playlist::getId).collect(Collectors.toList());
            List<Long> songs = songsOfPlaylists(playlistIds, 500);
            editorSongIds = Collections.unmodifiableList(songs);
            editorCachedAt = now;
        } catch (RuntimeException e) {
            log.warn("编辑精选查询失败 cause={}", e.toString());
        }
        return editorSongIds.size() <= limit ? editorSongIds
                : new ArrayList<>(editorSongIds.subList(0, Math.max(0, limit)));
    }

    /** 公开场景歌单里的歌，供 playlist_match 在用户无收藏时做内容匹配。 */
    public List<Long> publicPlaylistSongs(String genre, int limit) {
        try {
            List<Playlist> playlists = playlistMapper.selectList(Wrappers.<Playlist>lambdaQuery()
                    .eq(Playlist::getIsPublic, 1)
                    .eq(Playlist::getStatus, 1)
                    .eq(Playlist::getAuditStatus, 1)
                    .and(genre != null && !genre.isEmpty(), w -> w.like(Playlist::getTags, genre)
                            .or().eq(Playlist::getPlaylistType, Playlist.TYPE_SCENE))
                    .last("LIMIT 10"));
            return songsOfPlaylists(playlists.stream().map(Playlist::getId).collect(Collectors.toList()), limit);
        } catch (RuntimeException e) {
            log.warn("公开歌单查询失败 genre={} cause={}", genre, e.toString());
            return Collections.emptyList();
        }
    }

    /** 本地主题歌单（没有地区字段时的替代实现）。 */
    public List<Long> localSongs(int limit) {
        try {
            List<Playlist> playlists = playlistMapper.selectList(Wrappers.<Playlist>lambdaQuery()
                    .eq(Playlist::getIsPublic, 1)
                    .eq(Playlist::getStatus, 1)
                    .eq(Playlist::getAuditStatus, 1)
                    .and(w -> w.like(Playlist::getTags, "本地").or().like(Playlist::getTags, "城市"))
                    .last("LIMIT 10"));
            return songsOfPlaylists(playlists.stream().map(Playlist::getId).collect(Collectors.toList()), limit);
        } catch (RuntimeException e) {
            log.warn("本地歌单查询失败 cause={}", e.toString());
            return Collections.emptyList();
        }
    }

    /** 最近播放（去重、按时间倒序），recent_play 召回。 */
    public List<Long> recentSongIds(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            return playHistoryMapper.selectRecentSongIds(userId, limit);
        } catch (RuntimeException e) {
            log.warn("最近播放查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyList();
        }
    }

    /** 最近 90 天播放明细，用于 skip/completion 先验。 */
    public List<PlayHistory> recentHistory(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            return playHistoryMapper.selectRecentHistory(userId, LocalDateTime.now().minusDays(90), limit);
        } catch (RuntimeException e) {
            log.warn("播放明细查询失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyList();
        }
    }

    public Map<Long, PlayHistoryMapper.SongStatRow> songStats(Long userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        try {
            Map<Long, PlayHistoryMapper.SongStatRow> result = new LinkedHashMap<>();
            for (PlayHistoryMapper.SongStatRow row : playHistoryMapper.selectSongStats(userId)) {
                if (row.getSongId() != null) {
                    result.put(row.getSongId(), row);
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("单曲行为汇总失败 userId={} cause={}", userId, e.toString());
            return Collections.emptyMap();
        }
    }

    /** 新歌：发布 N 天内，按发布时间倒序。 */
    public List<Song> newSongs(int limit) {
        LocalDate since = LocalDate.now().minusDays(properties.getNewSongDays());
        List<Song> result = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (song.getReleaseDate() != null && !song.getReleaseDate().isBefore(since)) {
                result.add(song);
            }
        }
        result.sort(Comparator.comparing((Song s) -> s.getReleaseDate() == null
                        ? LocalDate.MIN : s.getReleaseDate()).reversed()
                .thenComparing(s -> s.getHotScore() == null ? 0 : s.getHotScore(), Comparator.reverseOrder()));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    }

    /** 长尾池：播放量位于后 50% 且非热门。 */
    public List<Song> longTailSongs(int limit) {
        List<Song> all = candidateService.playableSongs();
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> plays = all.stream().map(s -> s.getPlayCount() == null ? 0L : s.getPlayCount())
                .sorted().collect(Collectors.toList());
        long median = plays.get(plays.size() / 2);
        List<Song> result = all.stream()
                .filter(s -> (s.getPlayCount() == null ? 0L : s.getPlayCount()) <= median)
                .filter(s -> (s.getHotScore() == null ? 0 : s.getHotScore()) < 600)
                .sorted(Comparator.comparingLong((Song s) -> s.getPlayCount() == null ? 0L : s.getPlayCount())
                        .thenComparing(Song::getId))
                .limit(limit)
                .collect(Collectors.toList());
        return result;
    }

    /** 标签 → 歌曲：按用户标签权重取 top 标签，再取带这些标签的歌。 */
    public List<Long> tagMatchedSongs(Map<Long, Double> tagWeights, int limit, int topTags) {
        if (tagWeights == null || tagWeights.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> tags = tagWeights.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topTags)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (tags.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> tagSet = new HashSet<>(tags);
        List<Long> result = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            Set<Long> songTags = candidateService.tagsOf(song.getId());
            if (songTags.stream().anyMatch(tagSet::contains)) {
                result.add(song.getId());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** 歌手 → 歌曲。 */
    public List<Long> artistSongs(Collection<Long> artistIds, int limit) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = new HashSet<>(artistIds);
        List<Long> result = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (song.getArtistId() != null && ids.contains(song.getArtistId())) {
                result.add(song.getId());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** 编辑歌单曲目，按歌单内 sort_order 保序。 */
    private List<Long> songsOfPlaylists(Collection<Long> playlistIds, int limit) {
        if (playlistIds == null || playlistIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlaylistSong> rows = playlistSongMapper.selectList(Wrappers.<PlaylistSong>query()
                .in("playlist_id", playlistIds)
                .orderByAsc("playlist_id", "sort_order")
                .last("LIMIT " + Math.max(1, limit)));
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (PlaylistSong row : rows) {
            if (row.getSongId() != null) {
                ids.add(row.getSongId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 用户行为强度 → 冷启动阶段（FR-24-006）。 */
    public String coldStartStage(long behaviorCount) {
        if (behaviorCount <= 0) {
            return "S0";
        }
        if (behaviorCount <= 5) {
            return "S1";
        }
        if (behaviorCount <= 30) {
            return "S2";
        }
        return "S3";
    }

    /** 发布天数；无发布日期返回 {@link Integer#MAX_VALUE}。 */
    public int releaseDays(Song song) {
        if (song == null || song.getReleaseDate() == null) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(song.getReleaseDate(), LocalDate.now()));
    }
}
