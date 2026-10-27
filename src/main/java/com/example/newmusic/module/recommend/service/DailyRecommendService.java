package com.example.newmusic.module.recommend.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.vo.DailyRecommendVO;
import com.example.newmusic.module.recommend.vo.RecommendItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 每日推荐（FR-17-001）。
 *
 * <p>生成时机：用户首次请求时实时生成并缓存 48 小时；每天 00:10 的预热任务会
 * 为最近活跃用户提前生成。没有 XXL-Job，预热用 {@code @Scheduled} + 用户上限，
 * 生产环境换成分片任务即可，生成逻辑不变。
 *
 * <p>生成过程（对应 PRD 的多路召回/重排，模型部分用内容特征替代）：
 * 候选过滤 → 画像打分 → 冷启动混热门 → 10% 探索位 → 歌手≤2 → 分档内轻洗牌 → 写缓存。
 */
@Slf4j
@Service
public class DailyRecommendService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RecommendCache recommendCache;
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final CandidateService candidateService;
    private final RedisOps redisOps;
    private final RecommendProperties properties;

    public DailyRecommendService(RecommendCache recommendCache, ScoringService scoringService,
                                 FeedbackService feedbackService, CandidateService candidateService,
                                 RedisOps redisOps, RecommendProperties properties) {
        this.recommendCache = recommendCache;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.candidateService = candidateService;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public DailyRecommendVO getDaily(Long userId) {
        String date = LocalDate.now().format(DATE);
        String key = RedisKeys.recDaily(userId, date);
        Optional<DailyRecommendVO> cached = recommendCache.get(key, DailyRecommendVO.class);
        if (cached.isPresent()) {
            FeedbackService.BlockState block = feedbackService.loadState(userId);
            return filterBlocked(cached.get(), block.getDislikedSongs(), block.getDislikedArtists());
        }
        try {
            DailyRecommendVO vo = generate(userId, date);
            recommendCache.put(key, vo, Duration.ofHours(properties.getDailyCacheHours()));
            return vo;
        } catch (Exception e) {
            log.error("每日推荐生成失败，返回热门兜底 userId={} cause={}", userId, e.toString());
            return hotFallback(userId, date);
        }
    }

    /**
     * 生成并写入缓存。预热任务直接调用；请求路径上也复用它。
     */
    public DailyRecommendVO generateAndStore(Long userId, String date) {
        DailyRecommendVO vo = generate(userId, date);
        recommendCache.put(RedisKeys.recDaily(userId, date), vo,
                Duration.ofHours(properties.getDailyCacheHours()));
        return vo;
    }

    private DailyRecommendVO generate(Long userId, String date) {
        ScoringService.TasteProfile profile = scoringService.buildProfile(userId);
        FeedbackService.BlockState block = feedbackService.loadState(userId);
        Set<Long> blockedSongs = block.getDislikedSongs();
        Set<Long> blockedArtists = block.getDislikedArtists();
        Set<Long> exposed = exposedSongs(userId, date);

        List<Song> candidates = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (blockedSongs.contains(song.getId())
                    || (song.getArtistId() != null && blockedArtists.contains(song.getArtistId()))
                    || exposed.contains(song.getId())) {
                continue;
            }
            candidates.add(song);
        }
        if (candidates.isEmpty()) {
            return hotFallback(userId, date);
        }

        List<ScoringService.ScoredSong> ranked = scoringService.scoreAll(candidates, profile);
        shuffleWithinBands(ranked, userId == null ? 0 : userId, date);

        if (profile.isColdStart()) {
            ranked = mixColdStart(ranked, profile);
        }

        // BR-17-050：10% 探索位。取画像匹配度最低、且未听过的歌插进中段。
        int exploreCount = (int) Math.round(properties.getDailySize() * properties.getExplorationRatio());
        if (!profile.isColdStart() && exploreCount > 0) {
            ranked = injectExploration(ranked, profile, exploreCount);
        }

        int maxAlbum = Integer.MAX_VALUE; // 每日推荐只约束歌手（BR-17-007）
        List<RecommendItemVO> items = scoringService.selectDiverse(
                ranked, properties.getDailySize(),
                properties.getMaxArtistPerList(), maxAlbum,
                properties.getListenedRatioLimit(), Collections.<Long>emptySet());

        DailyRecommendVO vo = new DailyRecommendVO();
        vo.setDate(date);
        vo.setFallback(false);
        vo.setGeneratedAt(System.currentTimeMillis());
        vo.setItems(items);
        return vo;
    }

    /**
     * 冷启动：行为不足 5 条时，一半热门、一半问卷偏好。
     * 问卷也没填时自然退化为全热门（EX-17-006-1）。
     */
    private List<ScoringService.ScoredSong> mixColdStart(List<ScoringService.ScoredSong> ranked,
                                                         ScoringService.TasteProfile profile) {
        int hotCount = (int) Math.round(properties.getDailySize() * properties.getColdStartRatio());
        List<ScoringService.ScoredSong> byHot = new ArrayList<>(ranked);
        byHot.sort(Comparator.comparingInt((ScoringService.ScoredSong s) ->
                s.getSong().getHotScore() == null ? 0 : s.getSong().getHotScore()).reversed());
        List<ScoringService.ScoredSong> hot = new ArrayList<>();
        for (ScoringService.ScoredSong scored : byHot) {
            if (hot.size() >= hotCount) {
                break;
            }
            hot.add(scored);
        }
        Set<Long> hotIds = new HashSet<>();
        for (ScoringService.ScoredSong scored : hot) {
            hotIds.add(scored.getSong().getId());
        }
        boolean hasPreference = !profile.getGenreWeights().isEmpty()
                || !profile.getArtistWeights().isEmpty()
                || !profile.getLanguageWeights().isEmpty();
        if (!hasPreference) {
            // S0：问卷也没填，纯热门（EX-17-006-1），但仍要凑满 30 首
            return ranked;
        }
        List<ScoringService.ScoredSong> preference = new ArrayList<>();
        for (ScoringService.ScoredSong scored : ranked) {
            if (!hotIds.contains(scored.getSong().getId())) {
                preference.add(scored);
            }
        }
        List<ScoringService.ScoredSong> mixed = new ArrayList<>(ranked.size());
        mixed.addAll(hot);
        mixed.addAll(preference);
        if (mixed.isEmpty()) {
            return ranked;
        }
        return mixed;
    }

    /**
     * 探索位插入。探索歌从「未听过 + 热度/匹配度靠后」里挑，插入到约第 8/16/24 位，
     * 但仍会走 selectDiverse 的歌手上限，因此不会破坏多样性。
     */
    private List<ScoringService.ScoredSong> injectExploration(List<ScoringService.ScoredSong> ranked,
                                                              ScoringService.TasteProfile profile,
                                                              int count) {
        List<ScoringService.ScoredSong> pool = new ArrayList<>();
        int from = ranked.size() / 2;
        for (int i = from; i < ranked.size(); i++) {
            ScoringService.ScoredSong scored = ranked.get(i);
            if (!profile.getListenedSongIds().contains(scored.getSong().getId())
                    && scored.getScore() < 0.45) {
                pool.add(scored);
            }
        }
        if (pool.isEmpty()) {
            return ranked;
        }
        pool.sort(Comparator.comparingDouble(ScoringService.ScoredSong::getScore));
        List<ScoringService.ScoredSong> exploration = pool.subList(0, Math.min(count, pool.size()));
        Set<Long> explorationIds = new HashSet<>();
        for (ScoringService.ScoredSong scored : exploration) {
            explorationIds.add(scored.getSong().getId());
            scored.setReason(scored.getSong().getHotScore() != null && scored.getSong().getHotScore() < 900
                    ? "小众但好听" : "换换口味");
            scored.setRecall("explore");
        }

        List<ScoringService.ScoredSong> merged = new ArrayList<>(ranked.size());
        List<ScoringService.ScoredSong> explorationCopy = new ArrayList<>(exploration);
        int exploreIndex = 0;
        int[] insertAt = {7, 15, 23};
        for (ScoringService.ScoredSong scored : ranked) {
            if (explorationIds.contains(scored.getSong().getId())) {
                continue;
            }
            while (exploreIndex < explorationCopy.size()
                    && merged.size() == insertAt[Math.min(exploreIndex, insertAt.length - 1)]) {
                merged.add(explorationCopy.get(exploreIndex++));
            }
            merged.add(scored);
        }
        while (exploreIndex < explorationCopy.size()) {
            merged.add(explorationCopy.get(exploreIndex++));
        }
        return merged;
    }

    /** BR-17-008：Top 30 内部轻洗牌，按 10 首一档，档内打乱，不分档之间乱序。 */
    private void shuffleWithinBands(List<ScoringService.ScoredSong> ranked, long userId, String date) {
        Random random = new Random(userId * 31L + date.hashCode());
        int band = 10;
        for (int start = 0; start < ranked.size() && start < properties.getDailySize(); start += band) {
            int end = Math.min(start + band, ranked.size());
            for (int i = end - 1; i > start; i--) {
                int j = start + random.nextInt(i - start + 1);
                Collections.swap(ranked, i, j);
            }
        }
    }

    private DailyRecommendVO hotFallback(Long userId, String date) {
        Set<Long> blockedSongs = userId == null
                ? Collections.<Long>emptySet()
                : feedbackService.loadState(userId).getDislikedSongs();
        Set<Long> blockedArtists = userId == null
                ? Collections.<Long>emptySet()
                : feedbackService.loadState(userId).getDislikedArtists();
        List<ScoringService.ScoredSong> hot = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (blockedSongs.contains(song.getId())
                    || (song.getArtistId() != null && blockedArtists.contains(song.getArtistId()))) {
                continue;
            }
            ScoringService.ScoredSong scored = new ScoringService.ScoredSong();
            scored.setSong(song);
            scored.setScore(song.getHotScore() == null ? 0 : song.getHotScore());
            scored.setReason("大家都在听");
            scored.setRecall("hot");
            hot.add(scored);
        }
        List<RecommendItemVO> items = scoringService.selectDiverse(
                hot, properties.getDailySize(), properties.getMaxArtistPerList(),
                Integer.MAX_VALUE, 1.0, Collections.<Long>emptySet());
        DailyRecommendVO vo = new DailyRecommendVO();
        vo.setDate(date);
        vo.setFallback(true);
        vo.setGeneratedAt(System.currentTimeMillis());
        vo.setItems(items);
        return vo;
    }

    private DailyRecommendVO filterBlocked(DailyRecommendVO vo, Set<Long> blockedSongs,
                                           Set<Long> blockedArtists) {
        if (vo.getItems() == null || vo.getItems().isEmpty()) {
            return vo;
        }
        List<RecommendItemVO> filtered = new ArrayList<>(vo.getItems().size());
        for (RecommendItemVO item : vo.getItems()) {
            if (item.getSong() == null || blockedSongs.contains(item.getSong().getId())) {
                continue;
            }
            if (item.getSong().getArtistId() != null
                    && blockedArtists.contains(item.getSong().getArtistId())) {
                continue;
            }
            filtered.add(item);
        }
        vo.setItems(filtered);
        return vo;
    }

    /** 记录当天曝光（BR-17-003）。前端在元素进入视口 1 秒后批量上报。 */
    public void markExposure(Long userId, List<Long> songIds) {
        if (userId == null || songIds == null || songIds.isEmpty()) {
            return;
        }
        String key = RedisKeys.recExposure(userId, LocalDate.now().format(DATE));
        for (Long songId : songIds) {
            if (songId != null) {
                redisOps.addToSet(key, String.valueOf(songId), Duration.ofHours(24));
            }
        }
    }

    /** 口味问卷提交后需要立即看到新结果，因此删除当日缓存。 */
    public void invalidateToday(Long userId) {
        recommendCache.delete(RedisKeys.recDaily(userId, LocalDate.now().format(DATE)));
    }

    private Set<Long> exposedSongs(Long userId, String date) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String value : redisOps.members(RedisKeys.recExposure(userId, date))) {
            try {
                result.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 脏数据
            }
        }
        return result;
    }
}
