package com.example.newmusic.module.search.engine.mysql;

import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.engine.SearchCursor;
import com.example.newmusic.module.search.engine.SearchEngine;
import com.example.newmusic.module.search.engine.SearchHit;
import com.example.newmusic.module.search.engine.SearchPage;
import com.example.newmusic.module.search.engine.SearchRequest;
import com.example.newmusic.module.search.mapper.SearchDocMapper;
import com.example.newmusic.module.search.mapper.SearchRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MySQL 实现的搜索引擎（系分 D-33）。
 *
 * <p><b>它不是脚手架。</b>PRD 的 BR-15-009 要求「ES 不可用时降级为 MySQL LIKE 查询」，
 * 也就是说这条路径是 PRD 指定的永久交付项。把它做成端口下的一个实现而不是 ES 实现里的一段
 * 兜底代码，好处是降级成了「路由到另一个引擎」，而路由可以被单测覆盖
 * （{@code SearchEngineRouterTest}），不必真的把 ES 停掉才能验。
 *
 * <p><b>它同时也不是终局。</b>本书 §4.1 说明了为什么不用 MySQL 全文索引替代它；
 * 规模解法是装 ES（T631），不是继续压榨 MySQL。
 */
@Slf4j
@Component
@Order(100)
public class MysqlSearchEngine implements SearchEngine {

    /** 与 {@code newmusic.search.engine} 的配置值对应 */
    public static final String NAME = "mysql";

    /** 连续失败多少次判定为不可用 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 判定不可用后，多久重新试一次 */
    private static final long OPEN_MILLIS = 5_000L;

    private final SearchDocMapper mapper;
    private final SearchProperties properties;

    /**
     * 极简熔断器。
     *
     * <p>与模块 03 的 {@code RedisGuard} 同一个思路（D-20 快速失败）：数据库挂掉时，
     * 每个搜索请求都要付一次连接超时的代价，而 {@code SearchEngineRouter} 每请求都要问
     * 一次「你可用吗」。让前三次失败把开关打开，后面的请求直接判定不可用并降级，
     * 把「慢」变成「快而明确地降级」。
     *
     * <p>没有复用 {@code RedisGuard}：那个类的名字与语义都是「Redis 的守卫」，
     * 用它来表达「MySQL 的守卫」会让读代码的人以为搜索也依赖 Redis 才可用。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil = 0L;

    public MysqlSearchEngine(SearchDocMapper mapper, SearchProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return System.currentTimeMillis() >= openUntil;
    }

    @Override
    public SearchPage search(SearchRequest request) {
        try {
            SearchPage page = doSearch(request);
            consecutiveFailures.set(0);
            return page;
        } catch (RuntimeException e) {
            recordFailure(e);
            throw e;
        }
    }

    private SearchPage doSearch(SearchRequest request) {
        switch (request.getTarget()) {
            case ARTIST:
                return runAttached(request, mapper.searchArtists(request, cursorOf(request), limit(request), offset(request)),
                        mapper.countArtists(request), SearchRequest.Target.ARTIST);
            case ALBUM:
                return runAttached(request, mapper.searchAlbums(request, cursorOf(request), limit(request), offset(request)),
                        mapper.countAlbums(request), SearchRequest.Target.ALBUM);
            case PLAYLIST:
                return runAttached(request, mapper.searchPlaylists(request, cursorOf(request), limit(request), offset(request)),
                        mapper.countPlaylists(request), SearchRequest.Target.PLAYLIST);
            case SONG:
            case LYRIC:
            default:
                return searchSongs(request);
        }
    }

    private SearchPage searchSongs(SearchRequest request) {
        boolean lyricOnly = request.getTarget() == SearchRequest.Target.LYRIC;
        boolean includeUnplayable = properties.isIncludeUnplayable();
        SearchCursor.Pos pos = cursorOf(request);

        List<SearchRow> rows = mapper.searchSongs(request, sortKey(request), pos,
                includeUnplayable, lyricOnly, limit(request), offset(request));

        SearchPage page = new SearchPage();
        List<SearchHit> hits = new ArrayList<>(rows.size());
        for (SearchRow row : rows) {
            hits.add(toSongHit(row));
        }
        page.setHits(hits);
        page.setHasMore(rows.size() >= limit(request));
        // total 只在首页算：COUNT 要跑第二遍全表扫，与主查询同量级（§4.2）
        if (pos == null) {
            page.setTotal(mapper.countSongs(request, includeUnplayable, lyricOnly));
        }
        attachCursor(page);
        return page;
    }

    /** 歌手/专辑/歌单：排序恒为相关性（这几类没有「最新/时长」的可比字段），故不接游标排序键 */
    private SearchPage runAttached(SearchRequest request, List<SearchRow> rows, long total,
                                   SearchRequest.Target target) {
        SearchPage page = new SearchPage();
        List<SearchHit> hits = new ArrayList<>(rows.size());
        for (SearchRow row : rows) {
            hits.add(toAttachedHit(row, target));
        }
        page.setHits(hits);
        page.setHasMore(rows.size() >= limit(request));
        if (cursorOf(request) == null) {
            page.setTotal(total);
        }
        attachCursor(page);
        return page;
    }

    /**
     * 依据「还有没有下一页」决定要不要发游标。
     *
     * <p>只在 {@code hasMore} 时给：给一个已到末尾的游标，前端会多打一次必然为空的请求。
     * 游标用**本页最后一行**的排序键，这正是 {@code search_after} 的语义。
     */
    private void attachCursor(SearchPage page) {
        if (!page.isHasMore() || page.getHits().isEmpty()) {
            return;
        }
        SearchHit last = page.getHits().get(page.getHits().size() - 1);
        page.setNextCursor(SearchCursor.encode(last));
    }

    /** 首页 → {@code null}（用 OFFSET 翻页）；有游标 → 解出来的位置（用行值比较翻页） */
    private SearchCursor.Pos cursorOf(SearchRequest request) {
        return request.hasCursor() ? SearchCursor.decode(request.getCursor()) : null;
    }

    private int offset(SearchRequest request) {
        return request.hasCursor() ? 0 : request.offset();
    }

    private int limit(SearchRequest request) {
        return request.getPageSize();
    }

    private String sortKey(SearchRequest request) {
        return request.getSort().name().toLowerCase(Locale.ROOT);
    }

    private SearchHit toSongHit(SearchRow row) {
        SearchHit hit = new SearchHit();
        hit.setId(row.getId());
        hit.setTarget(SearchRequest.Target.SONG);
        hit.setScore(nullSafe(row.getScore()));
        hit.setName(row.getName());
        hit.setArtistId(row.getArtistId());
        hit.setArtistName(row.getArtistName());
        hit.setAlbumId(row.getAlbumId());
        hit.setAlbumName(row.getAlbumName());
        hit.setCover(row.getCover());
        hit.setLanguage(row.getLanguage());
        hit.setGenre(row.getGenre());
        hit.setEra(row.getEra());
        hit.setDuration(row.getDuration());
        hit.setHotScore(row.getHotScore());
        hit.setCopyrightStatus(row.getCopyrightStatus());
        hit.setPublishTime(row.getPublishTime());
        hit.setLyricFragment(row.getLyricFragment());

        mark(hit, SearchHit.Field.NAME, row.getMtName());
        mark(hit, SearchHit.Field.NAME_PINYIN, row.getMtNamePinyin());
        mark(hit, SearchHit.Field.NAME_PINYIN_FIRST, row.getMtNamePinyinFirst());
        mark(hit, SearchHit.Field.ARTIST_NAME, row.getMtArtistName());
        mark(hit, SearchHit.Field.ARTIST_NAME_PINYIN, row.getMtArtistNamePinyin());
        mark(hit, SearchHit.Field.ALBUM_NAME, row.getMtAlbumName());
        mark(hit, SearchHit.Field.TAG, row.getMtTag());
        mark(hit, SearchHit.Field.LYRIC, row.getMtLyric());
        return hit;
    }

    private SearchHit toAttachedHit(SearchRow row, SearchRequest.Target target) {
        SearchHit hit = new SearchHit();
        hit.setId(row.getId());
        hit.setTarget(target);
        hit.setScore(nullSafe(row.getScore()));
        hit.setName(row.getName());
        hit.setCover(row.getCover());
        hit.setArtistId(row.getArtistId());
        hit.setArtistName(row.getArtistName());
        hit.setSongCount(row.getSongCount());
        hit.setPlayCount(row.getPlayCount());
        hit.setHotScore(row.getHotScore());
        hit.setPublishTime(row.getPublishTime());
        // 命中溯源：**不能一律标成 name**。
        //
        // 歌手/专辑/歌单的打分表达式里同样有拼音项，歌手还有别名项（见 SearchDocMapper.xml），
        // 所以「非歌曲类的命中必然来自名称」这个前提是错的。搜 zjl 命中周杰伦时，
        // 那 4 分来自 name_pinyin_first：若报 matchedFields=["name"]，等于告诉前端
        // 「周杰伦这个名字里含 zjl」——前端会拿 "zjl" 去 "周杰伦" 里找高亮位置，一无所获，
        // 而真正的原因（首字母匹配）被这句话盖掉了。matchedFields 是前端唯一的命中来源说明，
        // 报错的字段名比不报更难查，所以这里逐列如实标记。
        //
        // 每个 target 的 SQL 只 SELECT 自己表达式用到的那几列，其余为 null，mark() 会跳过。
        mark(hit, SearchHit.Field.NAME, row.getMtName());
        mark(hit, SearchHit.Field.NAME_PINYIN, row.getMtNamePinyin());
        mark(hit, SearchHit.Field.NAME_PINYIN_FIRST, row.getMtNamePinyinFirst());
        mark(hit, SearchHit.Field.ALIAS, row.getMtAlias());
        mark(hit, SearchHit.Field.ARTIST_NAME, row.getMtArtistName());
        mark(hit, SearchHit.Field.TAG, row.getMtTag());
        return hit;
    }

    private void mark(SearchHit hit, SearchHit.Field field, Integer flag) {
        if (flag != null && flag > 0) {
            hit.mark(field);
        }
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private void recordFailure(RuntimeException e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            openUntil = System.currentTimeMillis() + OPEN_MILLIS;
            consecutiveFailures.set(0);
            log.error("MySQL 搜索引擎连续失败 {} 次，熔断 {}ms（期间搜索将降级或报 70001）",
                    failures, OPEN_MILLIS, e);
        } else {
            log.warn("MySQL 搜索引擎失败 {}/{} 次 cause={}", failures, FAILURE_THRESHOLD, e.toString());
        }
    }
}
