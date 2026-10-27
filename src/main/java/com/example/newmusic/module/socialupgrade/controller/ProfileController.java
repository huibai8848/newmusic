package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.socialupgrade.dto.ProfileUpdateReq;
import com.example.newmusic.module.socialupgrade.service.FanRankService;
import com.example.newmusic.module.socialupgrade.service.IntimacyService;
import com.example.newmusic.module.socialupgrade.service.MentionService;
import com.example.newmusic.module.socialupgrade.service.PostService;
import com.example.newmusic.module.socialupgrade.service.ProfileService;
import com.example.newmusic.module.socialupgrade.service.UserRecommendService;
import com.example.newmusic.module.socialupgrade.vo.FanRankVO;
import com.example.newmusic.module.socialupgrade.vo.IntimacyVO;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.example.newmusic.module.socialupgrade.vo.ProfileStatsVO;
import com.example.newmusic.module.socialupgrade.vo.ProfileVO;
import com.example.newmusic.module.socialupgrade.vo.RecommendUserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Api(tags = "社交升级-个人主页")
@RestController
@RequestMapping("/api/v1/users")
public class ProfileController {

    private final ProfileService profileService;
    private final PostService postService;
    private final UserRecommendService recommendService;
    private final IntimacyService intimacyService;
    private final FanRankService fanRankService;
    private final MentionService mentionService;

    public ProfileController(ProfileService profileService, PostService postService,
                             UserRecommendService recommendService,
                             IntimacyService intimacyService,
                             FanRankService fanRankService,
                             MentionService mentionService) {
        this.profileService = profileService;
        this.postService = postService;
        this.recommendService = recommendService;
        this.intimacyService = intimacyService;
        this.fanRankService = fanRankService;
        this.mentionService = mentionService;
    }

    @ApiOperation("用户主页")
    @GetMapping("/{userId}/profile")
    public R<ProfileVO> profile(@PathVariable Long userId, HttpServletRequest request) {
        return R.ok(profileService.getProfile(userId, UserContext.getUserId(),
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("更新我的资料")
    @PutMapping("/me/profile")
    @RateLimit(limit = 20, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "profile_update")
    public R<ProfileVO> update(@Valid @RequestBody ProfileUpdateReq req, HttpServletRequest request) {
        return R.ok(profileService.update(UserContext.requireUserId(), req,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("头像上传")
    @PostMapping("/me/avatar")
    public R<String> avatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return R.ok(profileService.updateAvatar(UserContext.requireUserId(), file,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("封面上传")
    @PostMapping("/me/cover")
    public R<String> cover(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return R.ok(profileService.updateCover(UserContext.requireUserId(), file,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("听歌统计（按隐私开关）")
    @GetMapping("/{userId}/stats")
    public R<ProfileStatsVO> stats(@PathVariable Long userId) {
        return R.ok(profileService.stats(userId, UserContext.getUserId()));
    }

    @ApiOperation("最近常听")
    @GetMapping("/{userId}/recent-listening")
    public R<List<Map<String, Object>>> recentListening(@PathVariable Long userId) {
        return R.ok(profileService.recentListening(userId, UserContext.getUserId()));
    }

    @ApiOperation("用户公开歌单")
    @GetMapping("/{userId}/playlists")
    public R<PageResult<Map<String, Object>>> playlists(@PathVariable Long userId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(profileService.playlists(userId, UserContext.getUserId(), page, size));
    }

    @ApiOperation("关于我")
    @GetMapping("/{userId}/about")
    public R<Map<String, Object>> about(@PathVariable Long userId) {
        return R.ok(profileService.about(userId, UserContext.getUserId()));
    }

    @ApiOperation("我的隐私设置")
    @GetMapping("/me/privacy")
    public R<Map<String, Object>> privacy() {
        return R.ok(profileService.getPrivacy(UserContext.requireUserId()));
    }

    @ApiOperation("更新隐私设置")
    @PutMapping("/me/privacy")
    public R<Map<String, Object>> updatePrivacy(@RequestBody Map<String, Object> req) {
        return R.ok(profileService.updatePrivacy(UserContext.requireUserId(), req));
    }

    @ApiOperation("用户动态列表")
    @GetMapping("/{userId}/posts")
    public R<PageResult<PostVO>> posts(@PathVariable Long userId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(postService.userPosts(userId, UserContext.getUserId(), page, size));
    }

    @ApiOperation("推荐关注（回关/二度/同城/热门）")
    @GetMapping("/recommend")
    public R<List<RecommendUserVO>> recommend(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(recommendService.recommend(UserContext.requireUserId(), limit));
    }

    @ApiOperation("忽略推荐（30 天内不再出现）")
    @PostMapping("/recommend/{userId}/ignore")
    public R<Void> ignoreRecommend(@PathVariable Long userId) {
        recommendService.ignore(UserContext.requireUserId(), userId);
        return R.ok();
    }

    @ApiOperation("亲密度（仅双方可见；对方关闭展示时 score 为空）")
    @GetMapping("/{userId}/intimacy")
    public R<IntimacyVO> intimacy(@PathVariable Long userId) {
        return R.ok(intimacyService.get(UserContext.requireUserId(), userId));
    }

    @ApiOperation(value = "粉丝榜", notes = "type：1 铁粉榜 / 2 互动榜 / 3 礼物榜（本期无数据源，返回空）")
    @GetMapping("/{userId}/fans-rank")
    public R<FanRankVO> fansRank(@PathVariable Long userId,
                                 @RequestParam(required = false) Integer type) {
        return R.ok(fanRankService.rank(userId, type));
    }

    @ApiOperation("@ 用户搜索")
    @GetMapping("/search")
    public R<List<UserBriefVO>> search(@RequestParam String keyword,
                                       @RequestParam(defaultValue = "10") int limit) {
        return R.ok(mentionService.search(UserContext.requireUserId(), keyword, limit));
    }
}
