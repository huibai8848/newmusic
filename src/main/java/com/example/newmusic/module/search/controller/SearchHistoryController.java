package com.example.newmusic.module.search.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.search.service.SearchHistoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索历史（FR-15-004 / BR-15-022~025）。
 *
 * <p><b>读写都按登录用户隔离</b>：三条接口第一行都是 {@link UserContext#requireUserId()}，
 * 未登录直接 401。历史是「我的」数据，没有游客版本——给游客按 IP 存一份历史，
 * 会在同一个 NAT 出口下把一家人的搜索记录混在一起，而用户无从知道这件事发生了。
 * 这与热搜不同：热搜**本来就**是匿名的聚合数据，游客的搜索理应计入（见 SearchService#record）。
 *
 * <p>限流维度是 {@code USER} 而不是默认的 {@code IP}：这三条都是登录接口，
 * 按 USER 计数才能让「同一个办公室的人」各自有独立额度。
 */
@Api(tags = "搜索-历史")
@RestController
@RequestMapping("/api/v1/search/history")
public class SearchHistoryController {

    private final SearchHistoryService searchHistoryService;

    public SearchHistoryController(SearchHistoryService searchHistoryService) {
        this.searchHistoryService = searchHistoryService;
    }

    @ApiOperation(value = "获取搜索历史",
            notes = "登录接口。最新在前，最多 20 条（BR-15-023）。空历史返回空数组而不是 404。")
    @GetMapping
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "search-history")
    public R<List<String>> list() {
        return R.ok(searchHistoryService.list(UserContext.requireUserId()));
    }

    @ApiOperation(value = "删除单条历史",
            notes = "登录接口。删不存在的词也返回成功（幂等）——用户点的是「删掉这个」而不是「删掉这个存在的这个」。")
    @DeleteMapping
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "search-history")
    public R<Void> remove(@ApiParam(value = "要删除的历史词", required = true) @RequestParam String keyword) {
        searchHistoryService.remove(UserContext.requireUserId(), keyword);
        return R.ok();
    }

    @ApiOperation(value = "清空搜索历史", notes = "登录接口。清空后 GET 必然返回空数组（BR-15-024）")
    @DeleteMapping("/all")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "search-history")
    public R<Void> clear() {
        searchHistoryService.clear(UserContext.requireUserId());
        return R.ok();
    }
}
