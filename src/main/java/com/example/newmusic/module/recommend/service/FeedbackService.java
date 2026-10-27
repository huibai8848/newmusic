package com.example.newmusic.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.dto.FeedbackReq;
import com.example.newmusic.module.recommend.entity.RecommendFeedback;
import com.example.newmusic.module.recommend.mapper.RecommendFeedbackMapper;
import com.example.newmusic.module.recommend.vo.BlocklistVO;
import com.example.newmusic.module.recommend.vo.FeedbackVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推荐反馈与屏蔽列表（FR-17-002）。
 *
 * <p>「当前屏蔽态」= 反馈日志按时间回放。Redis Set 只是回放结果的物化，
 * 用 {@code ml:rec:block:loaded:{userId}} 标记「已经回放过」，避免每次请求扫表。
 * Redis 不可用时直接回放数据库，因此屏蔽不会因为缓存故障而失效。
 */
@Slf4j
@Service
public class FeedbackService {

    private static final Duration STATE_MARKER_TTL = Duration.ofDays(7);

    private final RecommendFeedbackMapper feedbackMapper;
    private final SongMapper songMapper;
    private final CandidateService candidateService;
    private final RedisOps redisOps;
    private final RecommendCache recommendCache;
    private final RecommendProperties properties;
    private final ConcurrentHashMap<Long, Object> loadingLocks = new ConcurrentHashMap<>();

    public FeedbackService(RecommendFeedbackMapper feedbackMapper, SongMapper songMapper,
                           CandidateService candidateService, RedisOps redisOps,
                           RecommendCache recommendCache, RecommendProperties properties) {
        this.feedbackMapper = feedbackMapper;
        this.songMapper = songMapper;
        this.candidateService = candidateService;
        this.redisOps = redisOps;
        this.recommendCache = recommendCache;
        this.properties = properties;
    }

    @Transactional
    public FeedbackVO feedback(Long userId, FeedbackReq req) {
        String action = req.getAction() == null ? "" : req.getAction().trim().toUpperCase();
        Song song = req.getSongId() == null ? null : songMapper.selectById(req.getSongId());
        if (req.getSongId() != null && song == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌曲不存在");
        }
        Long artistId = req.getArtistId() != null ? req.getArtistId()
                : (song == null ? null : song.getArtistId());

        BlockState state = loadState(userId);
        switch (action) {
            case RecommendFeedback.LIKE:
                requireSong(song);
                append(userId, req, action, song.getId(), artistId);
                redisOps.addToSet(RedisKeys.recLike(userId), String.valueOf(song.getId()), null);
                redisOps.removeFromSet(RedisKeys.recDislike(userId), String.valueOf(song.getId()));
                break;
            case RecommendFeedback.DISLIKE:
                requireSong(song);
                if (!state.dislikedSongs.contains(song.getId())
                        && state.dislikedSongs.size() >= properties.getDislikedSongLimit()) {
                    throw new BizException(ResultCode.RECOMMEND_BLOCKLIST_FULL);
                }
                append(userId, req, action, song.getId(), artistId);
                redisOps.addToSet(RedisKeys.recDislike(userId), String.valueOf(song.getId()), null);
                redisOps.removeFromSet(RedisKeys.recLike(userId), String.valueOf(song.getId()));
                break;
            case RecommendFeedback.DISLIKE_ARTIST:
                if (artistId == null) {
                    throw new BizException(ResultCode.PARAM_INVALID, "DISLIKE_ARTIST 必须提供 artistId");
                }
                if (!state.dislikedArtists.contains(artistId)
                        && state.dislikedArtists.size() >= properties.getDislikedArtistLimit()) {
                    throw new BizException(ResultCode.RECOMMEND_BLOCKLIST_FULL);
                }
                append(userId, req, action, song == null ? null : song.getId(), artistId);
                redisOps.addToSet(RedisKeys.recDislikeArtist(userId), String.valueOf(artistId), null);
                break;
            case RecommendFeedback.UNDO:
                if (song == null && artistId == null) {
                    throw new BizException(ResultCode.PARAM_INVALID, "UNDO 必须提供 songId 或 artistId");
                }
                // 撤销歌曲时不能把随歌曲派生的 artistId 一起写进日志，
                // 否则回放会把「撤销歌曲不喜欢」误当成「撤销歌手屏蔽」
                append(userId, req, action, song == null ? null : song.getId(),
                        song == null ? artistId : null);
                if (song != null) {
                    redisOps.removeFromSet(RedisKeys.recDislike(userId), String.valueOf(song.getId()));
                    redisOps.removeFromSet(RedisKeys.recLike(userId), String.valueOf(song.getId()));
                } else {
                    redisOps.removeFromSet(RedisKeys.recDislikeArtist(userId), String.valueOf(artistId));
                }
                break;
            case RecommendFeedback.UNDO_ARTIST:
                if (artistId == null) {
                    throw new BizException(ResultCode.PARAM_INVALID, "UNDO_ARTIST 必须提供 artistId");
                }
                append(userId, req, action, null, artistId);
                redisOps.removeFromSet(RedisKeys.recDislikeArtist(userId), String.valueOf(artistId));
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的反馈类型：" + req.getAction());
        }

        // BR-17-020：反馈实时影响猜你喜欢；当日每日推荐缓存保持不变，读取时再过滤屏蔽项
        recommendCache.delete(RedisKeys.recGuess(userId));
        recommendCache.delete(RedisKeys.recNewcomer(userId));

        BlockState latest = loadState(userId);
        FeedbackVO vo = new FeedbackVO();
        vo.setAction(action);
        vo.setSongId(song == null ? null : song.getId());
        vo.setArtistId(artistId);
        vo.setDislikedSongCount(latest.dislikedSongs.size());
        vo.setDislikedArtistCount(latest.dislikedArtists.size());
        vo.setLikedSongCount(latest.likedSongs.size());
        return vo;
    }

    public BlocklistVO blocklist(Long userId) {
        BlockState state = loadState(userId);
        BlocklistVO vo = new BlocklistVO();
        List<Song> songs = state.dislikedSongs.isEmpty()
                ? Collections.<Song>emptyList()
                : songMapper.selectBatchIds(state.dislikedSongs);
        vo.setSongs(songs);
        List<Artist> artists = state.dislikedArtists.isEmpty()
                ? Collections.<Artist>emptyList()
                : new ArrayList<>(candidateService.artists(state.dislikedArtists).values());
        vo.setArtists(artists);
        vo.setSongLimit(properties.getDislikedSongLimit());
        vo.setArtistLimit(properties.getDislikedArtistLimit());
        return vo;
    }

    public void removeDislikedSong(Long userId, Long songId) {
        append(userId, null, RecommendFeedback.UNDO, songId, null);
        redisOps.removeFromSet(RedisKeys.recDislike(userId), String.valueOf(songId));
        recommendCache.delete(RedisKeys.recGuess(userId));
    }

    public void removeDislikedArtist(Long userId, Long artistId) {
        append(userId, null, RecommendFeedback.UNDO_ARTIST, null, artistId);
        redisOps.removeFromSet(RedisKeys.recDislikeArtist(userId), String.valueOf(artistId));
        recommendCache.delete(RedisKeys.recGuess(userId));
    }

    /** 派生出的当前屏蔽态。推荐服务在每次生成时读取一次，避免逐首查 Redis。 */
    public BlockState loadState(Long userId) {
        if (!redisOps.isAvailable()) {
            return replay(userId);
        }
        ensureLoaded(userId);
        BlockState state = new BlockState();
        state.dislikedSongs = parseIds(redisOps.members(RedisKeys.recDislike(userId)));
        state.dislikedArtists = parseIds(redisOps.members(RedisKeys.recDislikeArtist(userId)));
        state.likedSongs = parseIds(redisOps.members(RedisKeys.recLike(userId)));
        return state;
    }

    public Set<Long> likedSongIds(Long userId) {
        return new LinkedHashSet<>(loadState(userId).likedSongs);
    }

    private void ensureLoaded(Long userId) {
        if (redisOps.get(RedisKeys.recBlockLoaded(userId)).isPresent()) {
            return;
        }
        Object lock = loadingLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                if (redisOps.get(RedisKeys.recBlockLoaded(userId)).isPresent()) {
                    return;
                }
                BlockState state = replay(userId);
                redisOps.delete(RedisKeys.recDislike(userId));
                redisOps.delete(RedisKeys.recDislikeArtist(userId));
                redisOps.delete(RedisKeys.recLike(userId));
                for (Long id : state.dislikedSongs) {
                    redisOps.addToSet(RedisKeys.recDislike(userId), String.valueOf(id), null);
                }
                for (Long id : state.dislikedArtists) {
                    redisOps.addToSet(RedisKeys.recDislikeArtist(userId), String.valueOf(id), null);
                }
                for (Long id : state.likedSongs) {
                    redisOps.addToSet(RedisKeys.recLike(userId), String.valueOf(id), null);
                }
                redisOps.set(RedisKeys.recBlockLoaded(userId), "1", STATE_MARKER_TTL);
            } finally {
                loadingLocks.remove(userId, lock);
            }
        }
    }

    private BlockState replay(Long userId) {
        List<RecommendFeedback> rows = feedbackMapper.selectList(
                Wrappers.<RecommendFeedback>lambdaQuery()
                        .eq(RecommendFeedback::getUserId, userId)
                        .orderByAsc(RecommendFeedback::getCreateTime)
                        .orderByAsc(RecommendFeedback::getId));
        BlockState state = new BlockState();
        for (RecommendFeedback row : rows) {
            String action = row.getAction() == null ? "" : row.getAction();
            switch (action) {
                case RecommendFeedback.LIKE:
                    if (row.getSongId() != null) {
                        state.likedSongs.add(row.getSongId());
                        state.dislikedSongs.remove(row.getSongId());
                    }
                    break;
                case RecommendFeedback.DISLIKE:
                    if (row.getSongId() != null) {
                        state.dislikedSongs.add(row.getSongId());
                        state.likedSongs.remove(row.getSongId());
                    }
                    break;
                case RecommendFeedback.DISLIKE_ARTIST:
                    if (row.getArtistId() != null) {
                        state.dislikedArtists.add(row.getArtistId());
                    }
                    break;
                case RecommendFeedback.UNDO:
                    if (row.getSongId() != null) {
                        state.dislikedSongs.remove(row.getSongId());
                        state.likedSongs.remove(row.getSongId());
                    }
                    if (row.getArtistId() != null) {
                        state.dislikedArtists.remove(row.getArtistId());
                    }
                    break;
                case RecommendFeedback.UNDO_ARTIST:
                    if (row.getArtistId() != null) {
                        state.dislikedArtists.remove(row.getArtistId());
                    }
                    break;
                default:
                    break;
            }
        }
        return state;
    }

    private void append(Long userId, FeedbackReq req, String action, Long songId, Long artistId) {
        RecommendFeedback row = new RecommendFeedback();
        row.setUserId(userId);
        row.setSongId(songId);
        row.setArtistId(artistId);
        row.setAction(action);
        row.setScene(req == null || req.getScene() == null ? "UNKNOWN" : req.getScene().toUpperCase());
        row.setPosition(req == null ? null : req.getPosition());
        feedbackMapper.insert(row);
    }

    private void requireSong(Song song) {
        if (song == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "该操作必须提供 songId");
        }
    }

    private Set<Long> parseIds(Set<String> raw) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                ids.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏数据忽略
            }
        }
        return ids;
    }

    /** 当前用户的屏蔽/喜欢集合。 */
    public static class BlockState {
        private Set<Long> dislikedSongs = new HashSet<>();
        private Set<Long> dislikedArtists = new HashSet<>();
        private Set<Long> likedSongs = new HashSet<>();

        public Set<Long> getDislikedSongs() {
            return dislikedSongs;
        }

        public Set<Long> getDislikedArtists() {
            return dislikedArtists;
        }

        public Set<Long> getLikedSongs() {
            return likedSongs;
        }
    }
}
