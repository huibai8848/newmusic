package com.example.newmusic.module.search.service.impl;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.module.dict.support.DictQueryRewriter;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.dto.SearchQueryReq;
import com.example.newmusic.module.search.engine.SearchEngine;
import com.example.newmusic.module.search.engine.SearchEngineRouter;
import com.example.newmusic.module.search.engine.SearchHit;
import com.example.newmusic.module.search.engine.SearchPage;
import com.example.newmusic.module.search.engine.SearchRequest;
import com.example.newmusic.module.search.service.HotSearchService;
import com.example.newmusic.module.search.service.SearchHistoryService;
import com.example.newmusic.module.search.service.SearchService;
import com.example.newmusic.module.search.support.HighlightSupport;
import com.example.newmusic.module.search.support.PinyinSupport;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.search.vo.HotWordVO;
import com.example.newmusic.module.search.vo.SearchAlbumVO;
import com.example.newmusic.module.search.vo.SearchArtistVO;
import com.example.newmusic.module.search.vo.SearchPlaylistVO;
import com.example.newmusic.module.search.vo.SearchResultVO;
import com.example.newmusic.module.search.vo.SearchSongVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    // ---------- 综合 Tab 的配额（BR-15-004）。是**每类的上限**，不是总上限 ----------
    private static final int QUOTA_SONG = 10;
    private static final int QUOTA_ARTIST = 3;
    private static final int QUOTA_ALBUM = 3;
    private static final int QUOTA_PLAYLIST = 3;

    /** BR-15-010：单次结果窗口上限。超出必须改用游标分页 */
    private static final int MAX_RESULT_WINDOW = 1000;

    /** 无结果时给几个推荐词 */
    private static final int SUGGEST_WORD_LIMIT = 5;

    private final SearchEngineRouter router;
    private final SensitiveWordSupport sensitiveWordSupport;
    private final HotSearchService hotSearchService;
    private final SearchHistoryService searchHistoryService;
    private final SearchProperties properties;
    private final DictQueryRewriter dictQueryRewriter;

    public SearchServiceImpl(SearchEngineRouter router, SensitiveWordSupport sensitiveWordSupport,
                             HotSearchService hotSearchService, SearchHistoryService searchHistoryService,
                             SearchProperties properties, DictQueryRewriter dictQueryRewriter) {
        this.router = router;
        this.sensitiveWordSupport = sensitiveWordSupport;
        this.hotSearchService = hotSearchService;
        this.searchHistoryService = searchHistoryService;
        this.properties = properties;
        this.dictQueryRewriter = dictQueryRewriter;
    }

    @Override
    public SearchResultVO search(SearchQueryReq request) {
        long started = System.currentTimeMillis();
        request.normalize();

        SearchResultVO vo = new SearchResultVO();
        vo.setType(request.getType());

        // ---- 3. 敏感词（BR-15-002）。放在最前面：命中的请求不该被记录（BR-15-027），
        //         也不该先去打一遍搜索引擎再被丢弃
        if (request.hasKeyword()) {
            if (sensitiveWordSupport.isBlocked(request.getKeyword())) {
                throw new BizException(ResultCode.SEARCH_KEYWORD_ILLEGAL);
            }
            vo.setKeyword(request.getKeyword());
        }

        // ---- 4. 空关键词 → 热搜 + 历史（BR-15-001）
        if (!request.hasKeyword()) {
            return emptyKeyword(vo, started);
        }

        // ---- 5. 深分页校验（BR-15-010 / D-40）
        if (isPaginated(request) && request.getCursor() == null && offsetOf(request) >= MAX_RESULT_WINDOW) {
            throw new BizException(ResultCode.SEARCH_CURSOR_REQUIRED);
        }

        // ---- 5.5 词典查询改写（模块 11）：繁→简、首尾停用词、整词同义词、多音字拼音。
        //         改写只影响「拿去检索的词」；vo.keyword / 高亮 / 热搜历史仍用用户原文，
        //         否则运营看到的热搜词会变成「周杰伦」而不是用户实际搜的「周董」。
        DictQueryRewriter.RewriteResult rewrite = dictQueryRewriter.rewrite(request.getKeyword());

        // ---- 6. 降级路由
        SearchEngineRouter.Routed routed = router.pick();
        if (routed.isNone()) {
            return unavailable(vo, started);
        }
        vo.setDegraded(routed.isDegraded());
        vo.setEngine(routed.getEngine().name());
        if (routed.isDegraded()) {
            vo.setNotice("搜索服务已降级运行，结果可能不完整");
        }

        // ---- 7/8. 检索与组装
        if (SearchQueryReq.TYPE_ALL.equals(request.getType())) {
            fillAll(request, rewrite, vo, routed.getEngine());
        } else {
            fillSingle(request, rewrite, vo, routed.getEngine());
        }

        // 9. 异步记账（BR-15-006/007）。放在组装之后、返回之前：
        //    这样记账发生在「确认这是一次有效搜索」之后，不会为被降级兜底的请求计数
        record(request, vo);

        vo.setCostMs(System.currentTimeMillis() - started);
        return vo;
    }

    // ==================== 空关键词（BR-15-001） ====================

    private SearchResultVO emptyKeyword(SearchResultVO vo, long started) {
        vo.setDegraded(false);
        vo.setHotWords(hotSearchService.snapshot().getWords());

        LoginUser loginUser = UserContext.get();
        if (loginUser != null) {
            vo.setHistory(searchHistoryService.list(loginUser.getUserId()));
        }
        // 四个分段给空壳而不是省略：前端按「有 keyword 就渲染列表 Tab」分支，
        // 空壳让它在两种状态下用同一套渲染代码，不必再判一次字段是否存在
        vo.setSongs(emptyPage());
        vo.setArtists(emptyPage());
        vo.setAlbums(emptyPage());
        vo.setPlaylists(emptyPage());
        vo.setCostMs(System.currentTimeMillis() - started);
        return vo;
    }

    private <T> PageResult<T> emptyPage() {
        PageResult<T> page = new PageResult<>();
        page.setList(Collections.<T>emptyList());
        page.setTotal(0L);
        page.setPages(0L);
        page.setHasNext(false);
        return page;
    }

    // ==================== 全部引擎不可用（EX-15-013 第 5 级） ====================

    /**
     * 降级到「热门内容 + 提示」。
     *
     * <p><b>这里与 PRD 有一处必须说清的实现选择。</b>PRD 写的是「全部不可用 → 返回热门歌曲
     * + 提示『搜索服务暂时不可用』」，而 PRD 05 把 {@code 70001} 定为这条路径的错误码。
     * 但 {@code R} 的失败分支**不带 data**（{@code R.fail} 恒传 null），
     * 所以「代码是 70001」与「响应里带着热门歌曲」这两件事无法同时成立。
     *
     * <p>取舍：PRD 那句要求的实质是「用户仍能看到内容 + 得到一句解释」——那是一次
     * **成功但降级**的响应，而不是一次失败。因此：
     * <ul>
     *   <li>Redis 还能读到热搜词 → {@code code=0} + {@code degraded=true} + {@code notice}
     *       + {@code hotWords}（前端渲染成「为你推荐」）；</li>
     *   <li>连热搜都读不到（Redis 也挂了）→ 没有内容可给，此时才是真正的失败，抛 {@code 70001}。</li>
     * </ul>
     * 规则统一为「**能给出内容就用 code 0 + degraded 表达，什么都给不出才用 70001**」。
     */
    private SearchResultVO unavailable(SearchResultVO vo, long started) {
        List<HotWordVO> hotWords = hotSearchService.cachedWords();
        if (hotWords.isEmpty()) {
            throw new BizException(ResultCode.SEARCH_UNAVAILABLE);
        }
        vo.setDegraded(true);
        vo.setNotice(ResultCode.SEARCH_UNAVAILABLE.getMessage() + "，为你推荐热门内容");
        vo.setHotWords(hotWords);
        // 同样给空壳，理由见 emptyKeyword
        vo.setSongs(emptyPage());
        vo.setArtists(emptyPage());
        vo.setAlbums(emptyPage());
        vo.setPlaylists(emptyPage());
        vo.setCostMs(System.currentTimeMillis() - started);
        return vo;
    }

    // ==================== 综合 Tab：四次独立检索 ====================

    /**
     * 综合 Tab 用四次独立检索，而不是「查 19 条再分类」。
     *
     * <p>配额是**每类的上限**（BR-15-004）：歌曲 10、歌手 3、专辑 3、歌单 3。
     * 用「查 19 条再分类」会变成「谁在前 19 条里谁上」，于是热门歌手的歌会把
     * 歌手段挤空——那正好与配额想保证的事情相反。
     */
    private void fillAll(SearchQueryReq request, DictQueryRewriter.RewriteResult rewrite,
                         SearchResultVO vo, SearchEngine engine) {
        SearchPage songs = engine.search(buildRequest(request, rewrite, SearchRequest.Target.SONG, QUOTA_SONG));
        SearchPage artists = engine.search(buildRequest(request, rewrite, SearchRequest.Target.ARTIST, QUOTA_ARTIST));
        SearchPage albums = engine.search(buildRequest(request, rewrite, SearchRequest.Target.ALBUM, QUOTA_ALBUM));
        SearchPage playlists = engine.search(buildRequest(request, rewrite, SearchRequest.Target.PLAYLIST, QUOTA_PLAYLIST));

        String keyword = request.getKeyword();
        vo.setSongs(pageOf(songVOs(songs, keyword), songs.getTotal(), 1, QUOTA_SONG, songs.isHasMore()));
        vo.setArtists(pageOf(artistVOs(artists, keyword), artists.getTotal(), 1, QUOTA_ARTIST, artists.isHasMore()));
        vo.setAlbums(pageOf(albumVOs(albums, keyword), albums.getTotal(), 1, QUOTA_ALBUM, albums.isHasMore()));
        vo.setPlaylists(pageOf(playlistVOs(playlists, keyword), playlists.getTotal(), 1, QUOTA_PLAYLIST, playlists.isHasMore()));
        vo.setTotal(songs.getTotal());
        vo.setPageNum(1L);
        vo.setPageSize((long) QUOTA_SONG);
        vo.setHasMore(songs.isHasMore());

        if (isEmpty(songs, artists, albums, playlists)) {
            vo.setSuggestWords(suggestWords());
        }
    }

    private boolean isEmpty(SearchPage... pages) {
        for (SearchPage page : pages) {
            if (page.getHits() != null && !page.getHits().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ==================== 分类 Tab ====================

    private void fillSingle(SearchQueryReq request, DictQueryRewriter.RewriteResult rewrite,
                            SearchResultVO vo, SearchEngine engine) {
        SearchRequest engineRequest = buildRequest(request, rewrite, request.target(), request.getPageSize());
        engineRequest.setPageNum(request.getPageNum());
        engineRequest.setCursor(request.getCursor());

        SearchPage page = engine.search(engineRequest);
        String keyword = request.getKeyword();

        switch (request.target()) {
            case ARTIST:
                vo.setList(new ArrayList<Object>(artistVOs(page, keyword)));
                break;
            case ALBUM:
                vo.setList(new ArrayList<Object>(albumVOs(page, keyword)));
                break;
            case PLAYLIST:
                vo.setList(new ArrayList<Object>(playlistVOs(page, keyword)));
                break;
            case SONG:
            case LYRIC:
            default:
                vo.setList(new ArrayList<Object>(songVOs(page, keyword)));
                break;
        }
        vo.setTotal(page.getTotal());
        vo.setPageNum((long) request.getPageNum());
        vo.setPageSize((long) request.getPageSize());
        vo.setHasMore(page.isHasMore());
        vo.setCursor(page.getNextCursor());

        if (page.getHits() == null || page.getHits().isEmpty()) {
            // D-41：无结果不是错误。给空列表 + 推荐词，`70003` 不作为错误码返回
            vo.setSuggestWords(suggestWords());
        }
    }

    private List<String> suggestWords() {
        List<String> hot = hotSearchService.plainWords();
        return hot.size() <= SUGGEST_WORD_LIMIT ? hot : new ArrayList<>(hot.subList(0, SUGGEST_WORD_LIMIT));
    }

    /**
     * 拼装引擎请求。
     *
     * <p><b>拼音关键词由 {@link PinyinSupport#toSearchPinyin} 决定是否为 null</b>，
     * 这就是 BR-15-014 的落地：含中文时拼音列不参与，而不参与的方式是「传 null 让
     * SQL 里的 {@code LIKE} 恒不命中」，不需要在 SQL 里写分支（见 mapper 的注释）。
     *
     * <p><b>过滤器只对歌曲生效</b>：{@code language}/{@code genre}/{@code era}/{@code duration}
     * 都是 {@code t_song} 上的列，歌手/专辑/歌单没有这些维度。把它们照传到非歌曲检索上，
     * SQL 里也没有对应的列可筛，等于参数静默失效——不如在这里就不传，
     * 并在接口文档里写明「仅 type=song 生效」。
     */
    private SearchRequest buildRequest(SearchQueryReq source, DictQueryRewriter.RewriteResult rewrite,
                                       SearchRequest.Target target, int pageSize) {
        SearchRequest request = new SearchRequest();
        request.setKeyword(rewrite.getKeyword());
        request.setPinyinKeyword(rewrite.getPinyinKeyword());
        request.setTarget(target);
        request.setSort(target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC
                ? source.sortEnum() : SearchRequest.Sort.RELEVANCE);
        request.setPageNum(1);
        request.setPageSize(pageSize);
        if (target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC) {
            request.setLanguages(source.languages());
            request.setGenres(source.genres());
            request.setEras(source.eras());
            request.setDurations(source.durations());
        }
        return request;
    }

    // ==================== 命中 → VO（含高亮组装） ====================

    private List<SearchSongVO> songVOs(SearchPage page, String keyword) {
        List<SearchSongVO> list = new ArrayList<>(page.getHits().size());
        for (SearchHit hit : page.getHits()) {
            SearchSongVO vo = new SearchSongVO();
            vo.setId(hit.getId());
            vo.setName(hit.getName());
            vo.setArtistId(hit.getArtistId());
            vo.setArtistName(hit.getArtistName());
            vo.setAlbumId(hit.getAlbumId());
            vo.setAlbumName(hit.getAlbumName());
            vo.setCover(hit.getCover());
            vo.setDuration(hit.getDuration());
            vo.setLanguage(hit.getLanguage());
            vo.setGenre(hit.getGenre());
            vo.setEra(hit.getEra());
            vo.setHotScore(hit.getHotScore());
            vo.setScore(hit.getScore());
            vo.setMatchedFields(fieldNames(hit));
            vo.setCopyrightStatus(hit.getCopyrightStatus());
            vo.setPlayable(hit.getCopyrightStatus() != null && hit.getCopyrightStatus() == 1);

            Map<String, String> highlight = new LinkedHashMap<>();
            // 只在**原文真的含关键词**时才给高亮。拼音命中（搜 zjl 命中「周杰伦」）
            // 给出的是一个没有任何 <em> 的「高亮」，前端拿它去渲染反而会看不出差别，
            // 不如不给——命中原因由 matchedFields 说明
            putIfContains(highlight, "name", hit.getName(), keyword);
            putIfContains(highlight, "artistName", hit.getArtistName(), keyword);
            putIfContains(highlight, "albumName", hit.getAlbumName(), keyword);
            if (StringUtils.hasText(hit.getLyricFragment())) {
                String snippet = HighlightSupport.snippet(hit.getLyricFragment(), keyword);
                if (snippet != null) {
                    highlight.put("lyric", snippet);
                }
            }
            if (!highlight.isEmpty()) {
                vo.setHighlight(highlight);
            }
            list.add(vo);
        }
        return list;
    }

    private List<SearchArtistVO> artistVOs(SearchPage page, String keyword) {
        List<SearchArtistVO> list = new ArrayList<>(page.getHits().size());
        for (SearchHit hit : page.getHits()) {
            SearchArtistVO vo = new SearchArtistVO();
            vo.setId(hit.getId());
            vo.setName(hit.getName());
            vo.setCover(hit.getCover());
            vo.setSongCount(hit.getSongCount());
            vo.setHotScore(hit.getHotScore());
            vo.setScore(hit.getScore());
            vo.setMatchedFields(fieldNames(hit));
            Map<String, String> highlight = new LinkedHashMap<>();
            putIfContains(highlight, "name", hit.getName(), keyword);
            if (!highlight.isEmpty()) {
                vo.setHighlight(highlight);
            }
            list.add(vo);
        }
        return list;
    }

    private List<SearchAlbumVO> albumVOs(SearchPage page, String keyword) {
        List<SearchAlbumVO> list = new ArrayList<>(page.getHits().size());
        for (SearchHit hit : page.getHits()) {
            SearchAlbumVO vo = new SearchAlbumVO();
            vo.setId(hit.getId());
            vo.setName(hit.getName());
            vo.setCover(hit.getCover());
            vo.setArtistId(hit.getArtistId());
            vo.setArtistName(hit.getArtistName());
            vo.setSongCount(hit.getSongCount());
            vo.setPublishTime(hit.getPublishTime());
            vo.setHotScore(hit.getHotScore());
            vo.setScore(hit.getScore());
            vo.setMatchedFields(fieldNames(hit));
            Map<String, String> highlight = new LinkedHashMap<>();
            putIfContains(highlight, "name", hit.getName(), keyword);
            putIfContains(highlight, "artistName", hit.getArtistName(), keyword);
            if (!highlight.isEmpty()) {
                vo.setHighlight(highlight);
            }
            list.add(vo);
        }
        return list;
    }

    private List<SearchPlaylistVO> playlistVOs(SearchPage page, String keyword) {
        List<SearchPlaylistVO> list = new ArrayList<>(page.getHits().size());
        for (SearchHit hit : page.getHits()) {
            SearchPlaylistVO vo = new SearchPlaylistVO();
            vo.setId(hit.getId());
            vo.setName(hit.getName());
            vo.setCover(hit.getCover());
            vo.setSongCount(hit.getSongCount());
            vo.setPlayCount(hit.getPlayCount());
            vo.setHotScore(hit.getHotScore());
            vo.setScore(hit.getScore());
            vo.setMatchedFields(fieldNames(hit));
            Map<String, String> highlight = new LinkedHashMap<>();
            putIfContains(highlight, "name", hit.getName(), keyword);
            if (!highlight.isEmpty()) {
                vo.setHighlight(highlight);
            }
            list.add(vo);
        }
        return list;
    }

    /** 命中字段名小写，与 PRD 的字段名对齐（{@code name} / {@code lyric} / {@code name.pinyin}…） */
    private List<String> fieldNames(SearchHit hit) {
        List<String> names = new ArrayList<>(hit.getMatchedFields().size());
        for (SearchHit.Field field : hit.getMatchedFields()) {
            names.add(field.name().toLowerCase(Locale.ROOT).replace('_', '.'));
        }
        return names;
    }

    private void putIfContains(Map<String, String> target, String key, String raw, String keyword) {
        if (raw == null || keyword == null) {
            return;
        }
        if (raw.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
            target.put(key, HighlightSupport.highlight(raw, keyword));
        }
    }

    /**
     * @param total 命中总数；{@code null} 表示本次没有算（游标页不跑 COUNT）。
     *              综合 Tab 的四个分段不走游标，所以这里实际拿到的永远是算过的值；
     *              兜底成 0 只是为了让 {@link PageResult} 那个不可空的 {@code long} 有值可用
     */
    private <T> PageResult<T> pageOf(List<T> list, Long total, int pageNum, int pageSize, boolean hasMore) {
        PageResult<T> page = new PageResult<>();
        page.setList(list);
        page.setTotal(total == null ? 0L : total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        page.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        page.setHasNext(hasMore);
        return page;
    }

    // ==================== 异步记账 ====================

    /**
     * 热搜计数 + 搜索历史，都是异步的（BR-15-006/007）。
     *
     * <p>去重主体（{@code subject}）与限流用的是同一套标识：登录用 userId、游客用 IP。
     * 游客也计入热搜——否则「游客刷榜」只要不登录就不受 EX-15-005-1 约束；
     * 但游客**不记历史**（BR-15-006 只针对登录用户）。
     */
    private void record(SearchQueryReq request, SearchResultVO vo) {
        LoginUser loginUser = UserContext.get();
        long resultCount = vo.getTotal() == null ? 0L : vo.getTotal();
        String subject = loginUser != null
                ? "u:" + loginUser.getUserId()
                : "ip:" + currentIp();
        hotSearchService.recordAsync(request.getKeyword(), subject);
        if (loginUser != null) {
            searchHistoryService.recordAsync(loginUser.getUserId(), request.getKeyword(), (int) resultCount);
        }
    }

    private String currentIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非 HTTP 上下文（单测直调）：给一个不会与真实 IP 撞车的固定主体，
            // 保证「同一进程内的重复调用仍然会被去重」
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = IpUtil.getClientIp(request);
        return ip == null ? "unknown" : ip;
    }

    /** 哪些 type 有深分页问题。{@code all} 的四个分段各有小配额，永远到不了 1000 */
    private boolean isPaginated(SearchQueryReq request) {
        return !SearchQueryReq.TYPE_ALL.equals(request.getType());
    }

    /**
     * 偏移量。{@link PageQuery} 只给 pageNum/pageSize，没有 offset——
     * 在这里算而不是在 DTO 里加 {@code getOffset()}：只有本类需要它，
     * 加进 DTO 会让一个纯派生值出现在 Swagger 的请求参数表里，看起来像是可以传的
     */
    private int offsetOf(SearchQueryReq request) {
        return (request.getPageNum() - 1) * request.getPageSize();
    }

    /** 只用于日志与将来的调试入口；不参与业务判定 */
    @SuppressWarnings("unused")
    private boolean isIncludeUnplayable() {
        return properties.isIncludeUnplayable();
    }
}
