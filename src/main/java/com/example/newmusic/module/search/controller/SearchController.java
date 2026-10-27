package com.example.newmusic.module.search.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.search.dto.SearchQueryReq;
import com.example.newmusic.module.search.dto.SuggestQueryReq;
import com.example.newmusic.module.search.service.HotSearchService;
import com.example.newmusic.module.search.service.SearchService;
import com.example.newmusic.module.search.service.SuggestService;
import com.example.newmusic.module.search.support.SearchRateLimiter;
import com.example.newmusic.module.search.vo.HotSearchVO;
import com.example.newmusic.module.search.vo.SearchResultVO;
import com.example.newmusic.module.search.vo.SearchSuggestVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;

@Api(tags = "搜索-检索")
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final SuggestService suggestService;
    private final HotSearchService hotSearchService;
    private final SearchRateLimiter rateLimiter;

    public SearchController(SearchService searchService, SuggestService suggestService,
                            HotSearchService hotSearchService, SearchRateLimiter rateLimiter) {
        this.searchService = searchService;
        this.suggestService = suggestService;
        this.hotSearchService = hotSearchService;
        this.rateLimiter = rateLimiter;
    }

    @ApiOperation(value = "综合/分类搜索",
            notes = "公开接口（游客可调）。type=all 时返回歌曲/歌手/专辑/歌单四段（配额 10/3/3/3），"
                    + "其余按分类分页。keyword 为空时返回热搜 + 当前用户历史。"
                    + "无结果不是错误：返回 code 0 + 空列表 + suggestWords。")
    @GetMapping
    public R<SearchResultVO> search(SearchQueryReq request, HttpServletResponse response) {
        // 显式限流而不是 @RateLimit：阈值随登录态变（游客 30 / 登录 60），注解表达不了（D-39）
        rateLimiter.enforce();
        return finish(searchService.search(request), response);
    }

    /**
     * 分类快捷路径。
     *
     * <p><b>路径参数优先于 query 参数</b>：{@code GET /search/songs?type=artist} 按 song 处理。
     * 反过来（query 优先）会让 {@code /search/songs} 这个「说了要搜歌曲」的路径返回歌手，
     * 而路径比 query 更接近「用户点的那个 Tab」，冲突时应该由它说了算。
     *
     * <p>与 {@code /search} **不共用限流额度**：模块 03 的限流键按 api 分片
     * （{@code ml:limit:search:ip:1.2.3.4:GET:/api/v1/search/songs}），两个路径各自计数。
     * 这是沿用模块 03 的既有语义，已登记系分 §9 供评审是否要合并。
     */
    @ApiOperation(value = "分类搜索（快捷路径）", notes = "等价于 /search 且 type 固定，路径参数优先于 query 的 type")
    @GetMapping("/songs")
    public R<SearchResultVO> songs(SearchQueryReq request, HttpServletResponse response) {
        return byType(request, SearchQueryReq.TYPE_SONG, response);
    }

    @ApiOperation(value = "分类搜索-歌手（快捷路径）", notes = "等价于 /search 且 type=artist")
    @GetMapping("/artists")
    public R<SearchResultVO> artists(SearchQueryReq request, HttpServletResponse response) {
        return byType(request, SearchQueryReq.TYPE_ARTIST, response);
    }

    @ApiOperation(value = "分类搜索-专辑（快捷路径）", notes = "等价于 /search 且 type=album")
    @GetMapping("/albums")
    public R<SearchResultVO> albums(SearchQueryReq request, HttpServletResponse response) {
        return byType(request, SearchQueryReq.TYPE_ALBUM, response);
    }

    @ApiOperation(value = "分类搜索-歌单（快捷路径）", notes = "等价于 /search 且 type=playlist")
    @GetMapping("/playlists")
    public R<SearchResultVO> playlists(SearchQueryReq request, HttpServletResponse response) {
        return byType(request, SearchQueryReq.TYPE_PLAYLIST, response);
    }

    private R<SearchResultVO> byType(SearchQueryReq request, String type, HttpServletResponse response) {
        request.setType(type);
        rateLimiter.enforce();
        return finish(searchService.search(request), response);
    }

    /**
     * 联想。**限流比主检索宽得多（300/min）**：它跟着输入框逐字触发，
     * 用主检索那档 30/min 的话，用户打完「周杰伦」三个字就把额度耗光了。
     * 前缀查询本身有 5 分钟缓存（BR-15-020），300/min 的代价是可控的。
     */
    @ApiOperation(value = "搜索联想",
            notes = "按 歌手 > 歌曲 > 专辑 > 歌单 分组（BR-15-016）。keyword 为空只返回热搜词。"
                    + "highlight 仅在建议文本以关键词开头时给出。")
    @GetMapping("/suggest")
    @RateLimit(limit = 300, windowSeconds = 60, type = "search-suggest")
    public R<SearchSuggestVO> suggest(SuggestQueryReq request) {
        return R.ok(suggestService.suggest(request));
    }

    @ApiOperation(value = "热搜榜", notes = "5 分钟内所有人看到同一份榜单（BR-15-032）；置顶词恒在最前")
    @GetMapping("/hot")
    @RateLimit(limit = 60, windowSeconds = 60, type = "search-hot")
    public R<HotSearchVO> hot() {
        return R.ok(hotSearchService.snapshot());
    }

    /**
     * 响应体/响应头双写 {@code degraded}（D-48）。
     *
     * <p>头那份是给网关与监控的：它们不方便解析业务体，而按响应头做路由/告警是一行配置。
     * 体那份是给前端的：跨域下读自定义响应头要服务端额外开
     * {@code Access-Control-Expose-Headers}，漏配就静默读不到——两处都写，
     * 任何一方出问题都还有另一方能拿到这个事实。
     */
    private R<SearchResultVO> finish(SearchResultVO vo, HttpServletResponse response) {
        response.setHeader("degraded", String.valueOf(Boolean.TRUE.equals(vo.getDegraded())));
        if (vo.getEngine() != null) {
            response.setHeader("X-Search-Engine", vo.getEngine());
        }
        return R.ok(vo);
    }
}
