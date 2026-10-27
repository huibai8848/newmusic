package com.example.newmusic.module.recommend.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recommend.dto.ExposureReq;
import com.example.newmusic.module.recommend.dto.FeedbackReq;
import com.example.newmusic.module.recommend.dto.QuestionnaireReq;
import com.example.newmusic.module.recommend.service.ContinuePlayService;
import com.example.newmusic.module.recommend.service.DailyRecommendService;
import com.example.newmusic.module.recommend.service.FeedbackService;
import com.example.newmusic.module.recommend.service.GuessRecommendService;
import com.example.newmusic.module.recommend.service.QuestionnaireService;
import com.example.newmusic.module.recommend.service.RecommendService;
import com.example.newmusic.module.recommend.service.SimilarRecommendService;
import com.example.newmusic.module.recommend.vo.BlocklistVO;
import com.example.newmusic.module.recommend.vo.ContinuePlayVO;
import com.example.newmusic.module.recommend.vo.DailyRecommendVO;
import com.example.newmusic.module.recommend.vo.FeedbackVO;
import com.example.newmusic.module.recommend.vo.FrequentArtistVO;
import com.example.newmusic.module.recommend.vo.GuessRecommendVO;
import com.example.newmusic.module.recommend.vo.HomeRecommendVO;
import com.example.newmusic.module.recommend.vo.QuestionnaireVO;
import com.example.newmusic.module.recommend.vo.SimilarRecommendVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "AI 推荐")
@RestController
@RequestMapping("/api/v1/recommend")
public class RecommendController {

    private final DailyRecommendService dailyRecommendService;
    private final GuessRecommendService guessRecommendService;
    private final SimilarRecommendService similarRecommendService;
    private final ContinuePlayService continuePlayService;
    private final FeedbackService feedbackService;
    private final QuestionnaireService questionnaireService;
    private final RecommendService recommendService;

    public RecommendController(DailyRecommendService dailyRecommendService,
                               GuessRecommendService guessRecommendService,
                               SimilarRecommendService similarRecommendService,
                               ContinuePlayService continuePlayService,
                               FeedbackService feedbackService,
                               QuestionnaireService questionnaireService,
                               RecommendService recommendService) {
        this.dailyRecommendService = dailyRecommendService;
        this.guessRecommendService = guessRecommendService;
        this.similarRecommendService = similarRecommendService;
        this.continuePlayService = continuePlayService;
        this.feedbackService = feedbackService;
        this.questionnaireService = questionnaireService;
        this.recommendService = recommendService;
    }

    @ApiOperation(value = "每日推荐", notes = "固定 30 首（曲库不足时允许更少），48 小时缓存，每天 0 点后更新。")
    @GetMapping("/daily")
    @RateLimit(limit = 60, windowSeconds = 60, type = "recommend")
    public R<DailyRecommendVO> daily() {
        return R.ok(dailyRecommendService.getDaily(UserContext.requireUserId()));
    }

    @ApiOperation(value = "猜你喜欢", notes = "每次 20 首，支持 page 翻页；行为 <5 条时 coldStart=true 并降级热门。")
    @GetMapping("/guess")
    @RateLimit(limit = 60, windowSeconds = 60, type = "recommend")
    public R<GuessRecommendVO> guess(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return R.ok(guessRecommendService.guess(UserContext.requireUserId(), page, size));
    }

    @ApiOperation(value = "相似歌曲", notes = "公开接口；离线预计算优先，无数据时实时降级计算。")
    @GetMapping("/similar")
    @RateLimit(limit = 120, windowSeconds = 60, type = "recommend")
    public R<SimilarRecommendVO> similar(@ApiParam(value = "歌曲ID", required = true) @RequestParam Long songId) {
        return R.ok(similarRecommendService.getSimilar(songId, UserContext.getUserId()));
    }

    @ApiOperation(value = "首页推荐聚合", notes = "daily + guess + continuePlay + frequentArtists，单区块失败不拖垮整页。")
    @GetMapping("/home")
    @RateLimit(limit = 60, windowSeconds = 60, type = "recommend")
    public R<HomeRecommendVO> home() {
        return R.ok(recommendService.home(UserContext.requireUserId()));
    }

    @ApiOperation("继续播放")
    @GetMapping("/continue-play")
    public R<List<ContinuePlayVO>> continuePlay() {
        return R.ok(continuePlayService.continuePlay(UserContext.requireUserId()));
    }

    @ApiOperation("移除继续播放中的某一项")
    @DeleteMapping("/continue-play/{songId}")
    public R<Void> dismissContinue(@PathVariable Long songId) {
        continuePlayService.dismiss(UserContext.requireUserId(), songId);
        return R.ok();
    }

    @ApiOperation("最近常听歌手（30 天 Top 8）")
    @GetMapping("/frequent-artists")
    public R<List<FrequentArtistVO>> frequentArtists() {
        return R.ok(continuePlayService.frequentArtists(UserContext.requireUserId()));
    }

    @ApiOperation("最近播放歌曲（默认 12 首）")
    @GetMapping("/recent-songs")
    public R<List<Song>> recentSongs(@RequestParam(defaultValue = "12") int limit) {
        return R.ok(continuePlayService.recentSongs(UserContext.requireUserId(), Math.min(50, Math.max(1, limit))));
    }

    @ApiOperation("推荐反馈：喜欢/不喜欢/不喜欢歌手/撤销")
    @PostMapping("/feedback")
    @RateLimit(limit = 120, windowSeconds = 60, type = "recommend")
    public R<FeedbackVO> feedback(@Valid @RequestBody FeedbackReq req) {
        return R.ok(feedbackService.feedback(UserContext.requireUserId(), req));
    }

    @ApiOperation("屏蔽列表")
    @GetMapping("/blocklist")
    public R<BlocklistVO> blocklist() {
        return R.ok(feedbackService.blocklist(UserContext.requireUserId()));
    }

    @ApiOperation("从屏蔽列表移除歌曲")
    @DeleteMapping("/blocklist/song/{songId}")
    public R<Void> removeBlockedSong(@PathVariable Long songId) {
        feedbackService.removeDislikedSong(UserContext.requireUserId(), songId);
        return R.ok();
    }

    @ApiOperation("从屏蔽列表移除歌手")
    @DeleteMapping("/blocklist/artist/{artistId}")
    public R<Void> removeBlockedArtist(@PathVariable Long artistId) {
        feedbackService.removeDislikedArtist(UserContext.requireUserId(), artistId);
        return R.ok();
    }

    @ApiOperation("获取冷启动口味问卷")
    @GetMapping("/questionnaire")
    public R<QuestionnaireVO> questionnaire() {
        return R.ok(questionnaireService.get(UserContext.requireUserId()));
    }

    @ApiOperation("提交冷启动口味问卷")
    @PostMapping("/questionnaire")
    public R<QuestionnaireVO> saveQuestionnaire(@Valid @RequestBody QuestionnaireReq req) {
        return R.ok(questionnaireService.save(UserContext.requireUserId(), req));
    }

    @ApiOperation("推荐曝光上报（当日已曝光未点击的歌曲不再生成）")
    @PostMapping("/exposure")
    public R<Void> exposure(@Valid @RequestBody ExposureReq req) {
        dailyRecommendService.markExposure(UserContext.requireUserId(), req.getSongIds());
        return R.ok();
    }
}
