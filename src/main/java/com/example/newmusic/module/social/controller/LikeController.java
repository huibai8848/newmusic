package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.social.dto.LikeBatchCheckReq;
import com.example.newmusic.module.social.dto.LikeReq;
import com.example.newmusic.module.social.service.LikeService;
import com.example.newmusic.module.social.vo.LikeBatchCheckVO;
import com.example.newmusic.module.social.vo.LikeStatusVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "社交-点赞")
@RestController
@RequestMapping("/api/v1/likes")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @ApiOperation("点赞/切换点赞状态")
    @PostMapping
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_like")
    public R<LikeStatusVO> toggle(@Valid @RequestBody LikeReq req) {
        return R.ok(likeService.toggle(UserContext.requireUserId(), req));
    }

    @ApiOperation("取消点赞")
    @DeleteMapping
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_like")
    public R<LikeStatusVO> cancel(@Valid @RequestBody LikeReq req) {
        req.setLike(false);
        return R.ok(likeService.toggle(UserContext.requireUserId(), req));
    }

    @ApiOperation("查询点赞状态")
    @GetMapping
    public R<LikeStatusVO> status(@RequestParam String targetType,
                                  @RequestParam Long targetId) {
        return R.ok(likeService.status(UserContext.getUserId(), targetType, targetId));
    }

    @ApiOperation("批量查询点赞状态")
    @PostMapping("/batch-check")
    public R<LikeBatchCheckVO> batchCheck(@Valid @RequestBody LikeBatchCheckReq req) {
        return R.ok(likeService.batchCheck(UserContext.getUserId(), req));
    }

    @ApiOperation("我喜欢的歌曲")
    @GetMapping("/songs")
    public R<PageResult<Song>> songs(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return R.ok(likeService.myLikedSongs(UserContext.requireUserId(), page, size));
    }
}
