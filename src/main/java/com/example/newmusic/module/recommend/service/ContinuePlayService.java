package com.example.newmusic.module.recommend.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.play.service.PlayHistoryService;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.vo.ContinuePlayVO;
import com.example.newmusic.module.recommend.vo.FrequentArtistVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 继续播放、最近播放、最近常听（FR-17-007）。
 */
@Slf4j
@Service
public class ContinuePlayService {

    private static final Duration HIDDEN_TTL = Duration.ofDays(30);

    private final PlayHistoryService playHistoryService;
    private final PlayHistoryMapper playHistoryMapper;
    private final SongMapper songMapper;
    private final CandidateService candidateService;
    private final RecommendCache recommendCache;
    private final RedisOps redisOps;
    private final RecommendProperties properties;

    public ContinuePlayService(PlayHistoryService playHistoryService, PlayHistoryMapper playHistoryMapper,
                               SongMapper songMapper, CandidateService candidateService,
                               RecommendCache recommendCache, RedisOps redisOps,
                               RecommendProperties properties) {
        this.playHistoryService = playHistoryService;
        this.playHistoryMapper = playHistoryMapper;
        this.songMapper = songMapper;
        this.candidateService = candidateService;
        this.recommendCache = recommendCache;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public List<ContinuePlayVO> continuePlay(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Set<Long> hidden = hiddenSongs(userId);
        Map<Long, Song> playable = candidateService.playableMap();
        Set<Long> seen = new HashSet<>();
        List<ContinuePlayVO> result = new ArrayList<>();
        for (PlayHistory row : playHistoryService.continueRows(userId, 50)) {
            if (seen.contains(row.getSongId()) || hidden.contains(row.getSongId())) {
                continue;
            }
            seen.add(row.getSongId());
            Song song = playable.get(row.getSongId());
            if (song == null) {
                continue;
            }
            double duration = row.getSongDuration() == null ? 0 : row.getSongDuration();
            if (duration <= 0 && song.getDuration() != null) {
                duration = song.getDuration();
            }
            double position = row.getLastPosition() == null ? 0 : row.getLastPosition().doubleValue();
            if (duration <= 0 || position <= 0) {
                continue;
            }
            double progress = position / duration;
            if (progress < 0.1 || progress > 0.9) {
                continue;
            }
            ContinuePlayVO vo = new ContinuePlayVO();
            vo.setSong(song);
            vo.setPosition(Math.round(position * 10d) / 10d);
            vo.setDuration(duration);
            vo.setProgress(Math.round(progress * 1000d) / 1000d);
            result.add(vo);
            if (result.size() >= 10) {
                break;
            }
        }
        return result;
    }

    /** BR-17-058：移除继续播放中的某一项，30 天内不再出现。 */
    public void dismiss(Long userId, Long songId) {
        redisOps.addToSet(RedisKeys.recContinueHidden(userId), String.valueOf(songId), HIDDEN_TTL);
    }

    public List<Song> recentSongs(Long userId, int limit) {
        List<Long> ids = playHistoryService.recentSongIds(userId, Math.min(50, limit));
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Song> songs = songMapper.selectBatchIds(ids);
        Map<Long, Song> byId = new LinkedHashMap<>();
        for (Song song : songs) {
            byId.put(song.getId(), song);
        }
        List<Song> ordered = new ArrayList<>();
        for (Long id : ids) {
            Song song = byId.get(id);
            if (song != null) {
                ordered.add(song);
            }
            if (ordered.size() >= limit) {
                break;
            }
        }
        return ordered;
    }

    @SuppressWarnings("unchecked")
    public List<FrequentArtistVO> frequentArtists(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = RedisKeys.recFrequent(userId);
        Optional<FrequentArtistsCache> cached = recommendCache.get(key, FrequentArtistsCache.class);
        if (cached.isPresent()) {
            return cached.get().getItems();
        }
        List<PlayHistoryMapper.FrequentArtistRow> rows =
                playHistoryService.frequentArtists(userId, 30, 8);
        Set<Long> artistIds = new LinkedHashSet<>();
        for (PlayHistoryMapper.FrequentArtistRow row : rows) {
            if (row.getArtistId() != null) {
                artistIds.add(row.getArtistId());
            }
        }
        Map<Long, Artist> artists = candidateService.artists(artistIds);
        List<FrequentArtistVO> items = new ArrayList<>(rows.size());
        for (PlayHistoryMapper.FrequentArtistRow row : rows) {
            FrequentArtistVO vo = new FrequentArtistVO();
            vo.setArtistId(row.getArtistId());
            vo.setArtistName(row.getArtistName());
            vo.setPlayCount(row.getPlayCount());
            vo.setLastPlayTime(row.getLastPlayTime());
            Artist artist = artists.get(row.getArtistId());
            if (artist != null) {
                vo.setArtistName(artist.getName());
                vo.setAvatar(artist.getAvatar());
            }
            items.add(vo);
        }
        FrequentArtistsCache cache = new FrequentArtistsCache();
        cache.setItems(items);
        recommendCache.put(key, cache, Duration.ofMinutes(properties.getFrequentCacheMinutes()));
        return items;
    }

    private Set<Long> hiddenSongs(Long userId) {
        Set<Long> hidden = new HashSet<>();
        for (String value : redisOps.members(RedisKeys.recContinueHidden(userId))) {
            try {
                hidden.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏数据
            }
        }
        return hidden;
    }

    /** 缓存包装：Jackson 需要具体类型，不能直接反序列化 List。 */
    public static class FrequentArtistsCache {
        private List<FrequentArtistVO> items;

        public List<FrequentArtistVO> getItems() {
            return items;
        }

        public void setItems(List<FrequentArtistVO> items) {
            this.items = items;
        }
    }
}
