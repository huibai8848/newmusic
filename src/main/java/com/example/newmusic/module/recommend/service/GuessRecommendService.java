package com.example.newmusic.module.recommend.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.vo.GuessRecommendVO;
import com.example.newmusic.module.recommend.vo.RecommendItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 猜你喜欢（FR-17-003）。
 *
 * <p>与每日推荐的区别是实时性：30 分钟缓存，播放/反馈会主动删缓存。
 * 每次请求从缓存的候选池里按页切片，并**按页**应用歌手 ≤2、专辑 ≤1 的规则，
 * 而不是在生成池时一次性套死（否则第二页会几乎没有内容）。
 */
@Slf4j
@Service
public class GuessRecommendService {

    private final RecommendCache recommendCache;
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final CandidateService candidateService;
    private final RecommendProperties properties;

    public GuessRecommendService(RecommendCache recommendCache, ScoringService scoringService,
                                 FeedbackService feedbackService, CandidateService candidateService,
                                 RecommendProperties properties) {
        this.recommendCache = recommendCache;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.candidateService = candidateService;
        this.properties = properties;
    }

    public GuessRecommendVO guess(Long userId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(properties.getGuessSize(), pageSize));

        GuessPool pool = loadPool(userId);
        int need = safePage * safeSize;
        // 多选一页用来判断 hasNext。
        // selectDiverseItems 的 size 参数会截断结果，若只选到 need：
        //   - 用 >= need 判断 → 候选池恰好装满整数页时 hasNext=true，下一页却是空的；
        //   - 用 > need 判断 → 第一页永远 false（selected.size() 被截在 need）。
        // 贪心选择对前缀是稳定的，多选一页不会改变前面几页的切片结果。
        List<RecommendItemVO> selected = scoringService.selectDiverseItems(
                pool.items, need + safeSize, properties.getMaxArtistPerList(), properties.getMaxAlbumPerList(),
                properties.getGuessListenedRatioLimit());
        int from = Math.min((safePage - 1) * safeSize, selected.size());
        int to = Math.min(from + safeSize, selected.size());
        List<RecommendItemVO> pageItems = new ArrayList<>(selected.subList(from, to));
        for (int i = 0; i < pageItems.size(); i++) {
            pageItems.get(i).setPosition((safePage - 1) * safeSize + i + 1);
        }

        GuessRecommendVO vo = new GuessRecommendVO();
        vo.setPage(safePage);
        vo.setPageSize(safeSize);
        vo.setColdStart(pool.coldStart);
        vo.setItems(pageItems);
        // hasNext 问的是「本页之后还有没有」，不是「本页满没满」
        vo.setHasNext(selected.size() > need);
        return vo;
    }

    private GuessPool loadPool(Long userId) {
        String key = RedisKeys.recGuess(userId);
        Optional<GuessPool> cached = recommendCache.get(key, GuessPool.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        GuessPool pool = generate(userId);
        recommendCache.put(key, pool, Duration.ofMinutes(properties.getGuessCacheMinutes()));
        return pool;
    }

    private GuessPool generate(Long userId) {
        ScoringService.TasteProfile profile = scoringService.buildProfile(userId);
        FeedbackService.BlockState block = feedbackService.loadState(userId);
        Set<Long> blockedSongs = block.getDislikedSongs();
        Set<Long> blockedArtists = block.getDislikedArtists();

        List<Song> candidates = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (blockedSongs.contains(song.getId())
                    || (song.getArtistId() != null && blockedArtists.contains(song.getArtistId()))) {
                continue;
            }
            candidates.add(song);
        }

        List<ScoringService.ScoredSong> ranked = scoringService.scoreAll(candidates, profile);
        // BR-17-026：已听过的权重降低，但不完全排除
        for (ScoringService.ScoredSong scored : ranked) {
            if (scored.isListened()) {
                scored.setScore(scored.getScore() * 0.75);
            }
        }
        ranked.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        GuessPool pool = new GuessPool();
        pool.coldStart = profile.isColdStart();
        pool.generatedAt = System.currentTimeMillis();
        pool.items = new ArrayList<>();
        int limit = Math.min(properties.getGuessCacheSize(), ranked.size());
        for (int i = 0; i < limit; i++) {
            pool.items.add(scoringService.toItem(ranked.get(i), i + 1));
        }
        return pool;
    }

    /** 缓存结构：一次算好的候选池（最多 100 首），翻页时再按页做多样性裁剪。 */
    public static class GuessPool {
        private long generatedAt;
        private boolean coldStart;
        private List<RecommendItemVO> items;

        public long getGeneratedAt() {
            return generatedAt;
        }

        public void setGeneratedAt(long generatedAt) {
            this.generatedAt = generatedAt;
        }

        public boolean isColdStart() {
            return coldStart;
        }

        public void setColdStart(boolean coldStart) {
            this.coldStart = coldStart;
        }

        public List<RecommendItemVO> getItems() {
            return items;
        }

        public void setItems(List<RecommendItemVO> items) {
            this.items = items;
        }
    }
}
