package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.FollowReq;
import com.example.newmusic.module.social.service.FollowService;
import com.example.newmusic.module.social.vo.ArtistFollowStatusVO;
import com.example.newmusic.module.social.vo.FollowStatusVO;
import com.example.newmusic.module.social.vo.UserBriefVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "社交-关注")
@RestController
@RequestMapping("/api/v1")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @ApiOperation("关注用户")
    @PostMapping("/follows")
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_follow")
    public R<FollowStatusVO> follow(@Valid @RequestBody FollowReq req) {
        return R.ok(followService.follow(UserContext.requireUserId(), req.getTargetUserId()));
    }

    @ApiOperation("取关用户")
    @DeleteMapping("/follows/{userId}")
    public R<FollowStatusVO> unfollow(@PathVariable Long userId) {
        return R.ok(followService.unfollow(UserContext.requireUserId(), userId));
    }

    @ApiOperation("关注状态")
    @GetMapping("/follows/status")
    public R<FollowStatusVO> status(@RequestParam Long userId) {
        return R.ok(followService.status(UserContext.getUserId(), userId));
    }

    @ApiOperation("关注列表")
    @GetMapping("/follows/following")
    public R<PageResult<UserBriefVO>> following(@RequestParam(required = false) Long userId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Long viewerId = UserContext.getUserId();
        Long ownerId = userId == null ? viewerId : userId;
        return R.ok(followService.following(ownerId, viewerId, page, size));
    }

    @ApiOperation("粉丝列表")
    @GetMapping("/follows/followers")
    public R<PageResult<UserBriefVO>> followers(@RequestParam(required = false) Long userId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Long viewerId = UserContext.getUserId();
        Long ownerId = userId == null ? viewerId : userId;
        return R.ok(followService.followers(ownerId, viewerId, page, size));
    }

    @ApiOperation("关注歌手")
    @PostMapping("/artist-follows/{artistId}")
    public R<ArtistFollowStatusVO> followArtist(@PathVariable Long artistId) {
        return R.ok(followService.followArtist(UserContext.requireUserId(), artistId));
    }

    @ApiOperation("取关歌手")
    @DeleteMapping("/artist-follows/{artistId}")
    public R<ArtistFollowStatusVO> unfollowArtist(@PathVariable Long artistId) {
        return R.ok(followService.unfollowArtist(UserContext.requireUserId(), artistId));
    }

    @ApiOperation("歌手关注状态")
    @GetMapping("/artist-follows/{artistId}")
    public R<ArtistFollowStatusVO> artistStatus(@PathVariable Long artistId) {
        return R.ok(followService.artistStatus(UserContext.getUserId(), artistId));
    }
}
