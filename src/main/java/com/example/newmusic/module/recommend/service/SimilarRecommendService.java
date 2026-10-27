package com.example.newmusic.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.entity.SongSimilar;
import com.example.newmusic.module.recommend.mapper.SongSimilarMapper;
import com.example.newmusic.module.recommend.vo.RecommendItemVO;
import com.example.newmusic.module.recommend.vo.SimilarRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 相似歌曲（FR-17-004）。
 *
 * <p>离线任务每天 03:30 重算 Top 20 写入 {@code t_song_similar} 与 Redis；
 * 在线只读。新歌没有预计算数据时实时按同样公式降级计算（BR-17-035），
 * 算完顺手落库，下一次请求就命中。
 *
 * <p>相似度公式对齐 PRD：0.4 标签 Jaccard + 0.2 风格 + 0.1 语种 + 0.1 年代
 * + 0.1 BPM + 0.1 共现；没有向量服务，原公式里的「向量余弦」用标签/内容特征替代。
 */
@Slf4j
@Service
public class SimilarRecommendService {

    private static final BigDecimal SCORE_SCALE = BigDecimal.valueOf(10000);

    private final RecommendCache recommendCache;
    private final CandidateService candidateService;
    private final FeedbackService feedbackService;
    private final SongMapper songMapper;
    private final SongSimilarMapper similarMapper;
    private final PlayHistoryMapper playHistoryMapper;
    private final RecommendProperties properties;

    public SimilarRecommendService(RecommendCache recommendCache, CandidateService candidateService,
                                   FeedbackService feedbackService, SongMapper songMapper,
                                   SongSimilarMapper similarMapper, PlayHistoryMapper playHistoryMapper,
                                   RecommendProperties properties) {
        this.recommendCache = recommendCache;
        this.candidateService = candidateService;
        this.feedbackService = feedbackService;
        this.songMapper = songMapper;
        this.similarMapper = similarMapper;
        this.playHistoryMapper = playHistoryMapper;
        this.properties = properties;
    }

    public SimilarRecommendVO getSimilar(Long songId, Long userId) {
        Song target = songMapper.selectById(songId);
        if (target == null) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        FeedbackService.BlockState block = userId == null
                ? new FeedbackService.BlockState()
                : feedbackService.loadState(userId);
        Map<Long, Song> playable = candidateService.playableMap();

        Optional<SimilarRecommendVO> cached = recommendCache.get(
                RedisKeys.recSimilar(songId), SimilarRecommendVO.class);
        if (cached.isPresent()) {
            SimilarRecommendVO vo = sanitize(cached.get(), playable, block, songId);
            if (!vo.getItems().isEmpty()) {
                return vo;
            }
        }

        List<SongSimilar> rows = similarMapper.selectList(Wrappers.<SongSimilar>lambdaQuery()
                .eq(SongSimilar::getSongId, songId)
                .orderByAsc(SongSimilar::getRankNo)
                .last("LIMIT " + (properties.getSimilarSize() * 3)));
        if (!rows.isEmpty()) {
            SimilarRecommendVO vo = fromRows(songId, target, rows, playable, block);
            if (!vo.getItems().isEmpty()) {
                recommendCache.put(RedisKeys.recSimilar(songId), vo,
                        Duration.ofDays(properties.getSimilarCacheDays()));
                return vo;
            }
        }

        // EX-17-004-1 / BR-17-035：无预计算数据，实时降级并落库
        List<ScoringService.ScoredSong> computed = compute(songId, target);
        persist(songId, computed);
        SimilarRecommendVO vo = assemble(songId, target, computed, playable, block);
        recommendCache.put(RedisKeys.recSimilar(songId), vo,
                Duration.ofDays(properties.getSimilarCacheDays()));
        return vo;
    }

    /**
     * 离线重算全部歌曲。返回处理的歌曲数。
     *
     * <p>曲库规模大时应改为游标分批 + 并行；当前几十首直接全量重算。
     */
    public int precomputeAll() {
        List<Song> songs = candidateService.playableSongs();
        for (Song song : songs) {
            List<ScoringService.ScoredSong> computed = compute(song.getId(), song);
            persist(song.getId(), computed);
        }
        recommendCache.deleteByPrefix(RedisKeys.REC_SIMILAR);
        log.info("相似歌曲预计算完成 songs={}", songs.size());
        return songs.size();
    }

    private List<ScoringService.ScoredSong> compute(Long songId, Song target) {
        List<Song> candidates = candidateService.playableSongs();
        Map<Long, Set<Long>> tags = candidateService.songTags();
        Set<Long> targetTags = tags.getOrDefault(songId, Collections.<Long>emptySet());
        Map<Long, Long> cooccurrence = cooccurrence(songId);
        long maxCo = cooccurrence.values().stream().mapToLong(Long::longValue).max().orElse(0L);

        List<ScoringService.ScoredSong> scored = new ArrayList<>();
        for (Song candidate : candidates) {
            if (songId.equals(candidate.getId())) {
                continue;
            }
            double tagJaccard = jaccard(targetTags, tags.getOrDefault(candidate.getId(),
                    Collections.<Long>emptySet()));
            double genre = equal(target.getGenre(), candidate.getGenre());
            double language = equal(target.getLanguage(), candidate.getLanguage());
            double era = equal(target.getEra(), candidate.getEra());
            double bpm = bpmSimilarity(target.getBpm(), candidate.getBpm());
            double co = maxCo == 0 ? 0 : cooccurrence.getOrDefault(candidate.getId(), 0L) / (double) maxCo;
            double score = 0.4 * tagJaccard + 0.2 * genre + 0.1 * language
                    + 0.1 * era + 0.1 * bpm + 0.1 * co;

            ScoringService.ScoredSong item = new ScoringService.ScoredSong();
            item.setSong(candidate);
            item.setScore(score);
            item.setReason(trim("和《" + target.getName() + "》很像"));
            item.setRecall("similar");
            scored.add(item);
        }
        scored.sort(Comparator
                .comparingDouble(ScoringService.ScoredSong::getScore).reversed()
                .thenComparing((ScoringService.ScoredSong s) ->
                        s.getSong().getHotScore() == null ? 0 : s.getSong().getHotScore(), Comparator.reverseOrder()));
        return applyArtistCap(scored, properties.getSimilarSize(), properties.getMaxArtistPerList());
    }

    private Map<Long, Long> cooccurrence(Long songId) {
        List<PlayHistoryMapper.CoOccurrenceRow> rows = playHistoryMapper.selectCoOccurrencesForSong(
                songId, LocalDateTime.now().minusDays(90), 200);
        Map<Long, Long> map = new LinkedHashMap<>();
        for (PlayHistoryMapper.CoOccurrenceRow row : rows) {
            if (row.getSongId() != null) {
                map.put(row.getSongId(), row.getCoCount() == null ? 0L : row.getCoCount());
            }
        }
        return map;
    }

    private SimilarRecommendVO fromRows(Long songId, Song target, List<SongSimilar> rows,
                                        Map<Long, Song> playable, FeedbackService.BlockState block) {
        List<ScoringService.ScoredSong> scored = new ArrayList<>();
        for (SongSimilar row : rows) {
            Song song = playable.get(row.getSimilarSongId());
            if (song == null) {
                continue;
            }
            ScoringService.ScoredSong item = new ScoringService.ScoredSong();
            item.setSong(song);
            item.setScore(row.getScore() == null ? 0 : row.getScore().doubleValue());
            item.setReason(trim("和《" + target.getName() + "》很像"));
            item.setRecall("similar");
            scored.add(item);
        }
        scored = applyArtistCap(scored, properties.getSimilarSize(), properties.getMaxArtistPerList());
        return assemble(songId, target, scored, playable, block);
    }

    private SimilarRecommendVO assemble(Long songId, Song target, List<ScoringService.ScoredSong> scored,
                                        Map<Long, Song> playable, FeedbackService.BlockState block) {
        List<RecommendItemVO> items = new ArrayList<>();
        Set<Long> picked = new HashSet<>();
        Map<Long, Integer> artistCount = new HashMap<>();
        for (ScoringService.ScoredSong item : scored) {
            Song song = item.getSong();
            if (song == null || songId.equals(song.getId()) || picked.contains(song.getId())
                    || playable.get(song.getId()) == null
                    || block.getDislikedSongs().contains(song.getId())
                    || (song.getArtistId() != null && block.getDislikedArtists().contains(song.getArtistId()))) {
                continue;
            }
            if (song.getArtistId() != null
                    && artistCount.getOrDefault(song.getArtistId(), 0) >= properties.getMaxArtistPerList()) {
                continue;
            }
            items.add(scoringServiceItem(item, items.size() + 1));
            picked.add(song.getId());
            if (song.getArtistId() != null) {
                artistCount.merge(song.getArtistId(), 1, Integer::sum);
            }
            if (items.size() >= properties.getSimilarSize()) {
                break;
            }
        }

        boolean fallback = false;
        if (items.size() < properties.getSimilarSize()) {
            // EX-17-004-2 / EX-17-004-4：不足时用热门补足
            for (Song song : candidateService.playableSongs()) {
                if (items.size() >= properties.getSimilarSize()) {
                    break;
                }
                if (songId.equals(song.getId()) || picked.contains(song.getId())
                        || block.getDislikedSongs().contains(song.getId())
                        || (song.getArtistId() != null && block.getDislikedArtists().contains(song.getArtistId()))) {
                    continue;
                }
                if (song.getArtistId() != null
                        && artistCount.getOrDefault(song.getArtistId(), 0) >= properties.getMaxArtistPerList()) {
                    continue;
                }
                ScoringService.ScoredSong hot = new ScoringService.ScoredSong();
                hot.setSong(song);
                hot.setScore(song.getHotScore() == null ? 0 : song.getHotScore() / 1000.0);
                hot.setReason("大家都在听");
                hot.setRecall("hot");
                items.add(scoringServiceItem(hot, items.size() + 1));
                picked.add(song.getId());
                if (song.getArtistId() != null) {
                    artistCount.merge(song.getArtistId(), 1, Integer::sum);
                }
                fallback = true;
            }
        }

        SimilarRecommendVO vo = new SimilarRecommendVO();
        vo.setSongId(songId);
        vo.setFallback(fallback);
        vo.setItems(items);
        return vo;
    }

    private void persist(Long songId, List<ScoringService.ScoredSong> scored) {
        similarMapper.deleteBySongPhysically(songId);
        int rank = 0;
        for (ScoringService.ScoredSong item : scored) {
            SongSimilar row = new SongSimilar();
            row.setSongId(songId);
            row.setSimilarSongId(item.getSong().getId());
            row.setScore(BigDecimal.valueOf(Math.round(item.getScore() * 10000d))
                    .divide(SCORE_SCALE, 4, BigDecimal.ROUND_HALF_UP));
            row.setRankNo(++rank);
            similarMapper.insert(row);
        }
    }

    /** 只保留仍然可播放、且未被屏蔽的条目，避免缓存里留下已下架歌曲。 */
    private SimilarRecommendVO sanitize(SimilarRecommendVO vo, Map<Long, Song> playable,
                                        FeedbackService.BlockState block, Long songId) {
        List<RecommendItemVO> items = new ArrayList<>();
        Map<Long, Integer> artistCount = new HashMap<>();
        if (vo.getItems() != null) {
            for (RecommendItemVO item : vo.getItems()) {
                Song song = item.getSong();
                if (song == null || songId.equals(song.getId())
                        || playable.get(song.getId()) == null
                        || block.getDislikedSongs().contains(song.getId())
                        || (song.getArtistId() != null
                        && block.getDislikedArtists().contains(song.getArtistId()))) {
                    continue;
                }
                if (song.getArtistId() != null
                        && artistCount.getOrDefault(song.getArtistId(), 0) >= properties.getMaxArtistPerList()) {
                    continue;
                }
                item.setPosition(items.size() + 1);
                items.add(item);
                if (song.getArtistId() != null) {
                    artistCount.merge(song.getArtistId(), 1, Integer::sum);
                }
            }
        }
        vo.setItems(items);
        return vo;
    }

    private List<ScoringService.ScoredSong> applyArtistCap(List<ScoringService.ScoredSong> scored,
                                                           int size, int maxArtist) {
        List<ScoringService.ScoredSong> result = new ArrayList<>();
        Map<Long, Integer> artistCount = new HashMap<>();
        for (ScoringService.ScoredSong item : scored) {
            Long artistId = item.getSong().getArtistId();
            if (artistId != null && artistCount.getOrDefault(artistId, 0) >= maxArtist) {
                continue;
            }
            result.add(item);
            if (artistId != null) {
                artistCount.merge(artistId, 1, Integer::sum);
            }
            if (result.size() >= size) {
                break;
            }
        }
        return result;
    }

    private RecommendItemVO scoringServiceItem(ScoringService.ScoredSong scored, int position) {
        RecommendItemVO item = new RecommendItemVO();
        item.setSong(scored.getSong());
        item.setReason(scored.getReason());
        item.setRecall(scored.getRecall());
        item.setScore(Math.round(scored.getScore() * 10000d) / 10000d);
        item.setPosition(position);
        item.setListened(false);
        return item;
    }

    private double jaccard(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (Long value : a) {
            if (b.contains(value)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : intersection / (double) union;
    }

    private double equal(String a, String b) {
        return a != null && a.equals(b) ? 1.0 : 0.0;
    }

    private double bpmSimilarity(Integer a, Integer b) {
        if (a == null || b == null || a <= 0 || b <= 0) {
            return 0;
        }
        return Math.max(0, 1 - Math.abs(a - b) / 80.0);
    }

    private String trim(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "相似歌曲";
        }
        return reason.length() <= 20 ? reason : reason.substring(0, 20);
    }
}
