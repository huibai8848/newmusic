package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.socialupgrade.service.DiscoverService;
import com.example.newmusic.module.socialupgrade.service.FeedService;
import com.example.newmusic.module.socialupgrade.vo.FeedVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "社交升级-Feed")
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;
    private final DiscoverService discoverService;

    public FeedController(FeedService feedService, DiscoverService discoverService) {
        this.feedService = feedService;
        this.discoverService = discoverService;
    }

    @ApiOperation(value = "关注流", notes = "cursor 形如 {lastScore}_{lastPostId}，留空取第一页")
    @GetMapping("/following")
    public R<FeedVO> following(@RequestParam(required = false) String cursor,
                               @RequestParam(defaultValue = "20") int limit) {
        return R.ok(feedService.following(UserContext.requireUserId(), cursor, limit));
    }

    @ApiOperation(value = "发现流", notes = "cursor 为偏移量；已看过的动态会累计曝光次数，达到 3 次后不再出现")
    @GetMapping("/discover")
    public R<FeedVO> discover(@RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "20") int limit) {
        return R.ok(discoverService.discover(UserContext.requireUserId(), cursor, limit));
    }

    @ApiOperation("把关注流标记为已读（新内容计数清零）")
    @PostMapping("/read")
    public R<Void> read() {
        feedService.markRead(UserContext.requireUserId());
        return R.ok();
    }
}
