package com.example.newmusic.module.recommend.service;

import com.example.newmusic.module.recommend.vo.ContinuePlayVO;
import com.example.newmusic.module.recommend.vo.DailyRecommendVO;
import com.example.newmusic.module.recommend.vo.FrequentArtistVO;
import com.example.newmusic.module.recommend.vo.GuessRecommendVO;
import com.example.newmusic.module.recommend.vo.HomeRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页推荐聚合。
 *
 * <p>单个区块失败不拖垮整个首页：每个区块独立 try-catch，失败的区块返回空并把
 * 名称写进 {@code degraded}，前端可以按需隐藏或提示（EX-17-003-4 的思路）。
 */
@Slf4j
@Service
public class RecommendService {

    private final DailyRecommendService dailyRecommendService;
    private final GuessRecommendService guessRecommendService;
    private final ContinuePlayService continuePlayService;

    public RecommendService(DailyRecommendService dailyRecommendService,
                            GuessRecommendService guessRecommendService,
                            ContinuePlayService continuePlayService) {
        this.dailyRecommendService = dailyRecommendService;
        this.guessRecommendService = guessRecommendService;
        this.continuePlayService = continuePlayService;
    }

    public HomeRecommendVO home(Long userId) {
        HomeRecommendVO vo = new HomeRecommendVO();
        List<String> degraded = new ArrayList<>();

        DailyRecommendVO daily = null;
        try {
            daily = dailyRecommendService.getDaily(userId);
        } catch (RuntimeException e) {
            log.warn("首页每日推荐降级 userId={} cause={}", userId, e.toString());
            degraded.add("daily");
        }
        vo.setDaily(daily);

        GuessRecommendVO guess = null;
        try {
            guess = guessRecommendService.guess(userId, 1, 20);
        } catch (RuntimeException e) {
            log.warn("首页猜你喜欢降级 userId={} cause={}", userId, e.toString());
            degraded.add("guess");
        }
        vo.setGuess(guess);
        vo.setColdStart(guess != null && guess.isColdStart());

        try {
            List<ContinuePlayVO> continuePlay = continuePlayService.continuePlay(userId);
            vo.setContinuePlay(continuePlay);
        } catch (RuntimeException e) {
            log.warn("继续播放降级 userId={} cause={}", userId, e.toString());
            vo.setContinuePlay(new ArrayList<>());
            degraded.add("continue");
        }

        try {
            List<FrequentArtistVO> frequent = continuePlayService.frequentArtists(userId);
            vo.setFrequentArtists(frequent);
        } catch (RuntimeException e) {
            log.warn("最近常听降级 userId={} cause={}", userId, e.toString());
            vo.setFrequentArtists(new ArrayList<>());
            degraded.add("frequent");
        }

        vo.setDegraded(degraded);
        return vo;
    }
}
