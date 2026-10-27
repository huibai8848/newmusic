package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.BlacklistReq;
import com.example.newmusic.module.social.service.BlacklistService;
import com.example.newmusic.module.social.vo.BlacklistVO;
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

@Api(tags = "社交-黑名单")
@RestController
@RequestMapping("/api/v1/blacklist")
public class BlacklistController {

    private final BlacklistService blacklistService;

    public BlacklistController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    @ApiOperation(value = "拉黑用户", notes = "拉黑后自动解除双向关注，双方不能再评论/私信/关注。")
    @PostMapping
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_blacklist")
    public R<Void> add(@Valid @RequestBody BlacklistReq req) {
        blacklistService.add(UserContext.requireUserId(), req.getTargetUserId());
        return R.ok();
    }

    @ApiOperation("解除拉黑")
    @DeleteMapping("/{userId}")
    public R<Void> remove(@PathVariable Long userId) {
        blacklistService.remove(UserContext.requireUserId(), userId);
        return R.ok();
    }

    @ApiOperation("黑名单列表")
    @GetMapping
    public R<PageResult<BlacklistVO>> page(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return R.ok(blacklistService.page(UserContext.requireUserId(), page, size));
    }
}
