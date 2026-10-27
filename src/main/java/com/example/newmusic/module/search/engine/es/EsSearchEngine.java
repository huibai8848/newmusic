package com.example.newmusic.module.search.engine.es;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.support.EsClient;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.engine.SearchCursor;
import com.example.newmusic.module.search.engine.SearchEngine;
import com.example.newmusic.module.search.engine.SearchHit;
import com.example.newmusic.module.search.engine.SearchPage;
import com.example.newmusic.module.search.engine.SearchRequest;
import com.example.newmusic.module.search.engine.SearchEngineRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Elasticsearch 搜索引擎（模块 11，PRD 20 FR-20-007）。
 *
 * <p>只在 {@code newmusic.dict.es.enabled=true} 且 ES 可达时被路由选中；
 * 其余情况 {@link #isAvailable()} 返回 false，{@link SearchEngineRouter} 自动降级到
 * {@code MysqlSearchEngine}（BR-15-009 / EX-20-007-1）。
 *
 * <p>索引结构见 {@code src/main/resources/es/music_song-index.json}：
 * 歌曲/歌手/专辑/歌单共用一个索引，用 {@code docType} 区分——四类文档的检索字段高度重叠，
 * 拆四个索引会让「综合 Tab 四次检索」变成四次跨索引查询，反而更慢。
 */
@Slf4j
@Component
@Order(50)
public class EsSearchEngine implements SearchEngine {

    public static final String NAME = "es";

    private final EsClient esClient;
    private final ObjectMapper objectMapper;
    private final DictProperties.Es esProperties;
    private final SearchProperties searchProperties;

    public EsSearchEngine(EsClient esClient, ObjectMapper objectMapper,
                          DictProperties dictProperties, SearchProperties searchProperties) {
        this.esClient = esClient;
        this.objectMapper = objectMapper;
        this.esProperties = dictProperties.getEs();
        this.searchProperties = searchProperties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return esProperties.isEnabled() && esClient.isAvailable();
    }

    @Override
    public SearchPage search(SearchRequest request) {
        String index = esProperties.getIndexAlias();
        ObjectNode body = buildBody(request);
        JsonNode response = esClient.search(index, body);
        return toPage(request, response);
    }

    // ==================== 查询构造 ====================

    private ObjectNode buildBody(SearchRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("track_total_hits", !request.hasCursor());
        if (request.hasCursor()) {
            body.put("size", request.getPageSize());
            body.set("search_after", searchAfter(request));
        } else {
            body.put("from", request.offset());
            body.put("size", request.getPageSize());
        }

        ObjectNode bool = objectMapper.createObjectNode();
        ArrayNode must = bool.putArray("must");
        must.add(keywordQuery(request));
        if (request.getTarget() == SearchRequest.Target.LYRIC) {
            ObjectNode lyric = objectMapper.createObjectNode();
            lyric.putObject("match").putObject("lyric")
                    .put("query", request.getKeyword())
                    .put("operator", "and");
            must.add(lyric);
        }

        ArrayNode filter = bool.putArray("filter");
        filter.add(term("docType", docType(request.getTarget())));
        // 版权/语种/流派/年代/时长这些筛选只存在于歌曲文档（MySQL 实现也只在 songWhere 里加）。
        // 对歌手/专辑/歌单无条件加会命中 0 条：它们没有 copyrightStatus 字段，
        // `term` 查询遇到缺失字段恒不匹配——表现是「切到歌手 Tab 永远空」。
        if (isSongLike(request.getTarget())) {
            // 字段名必须与 mapping 一致（camelCase）。dynamic=false 下写错名字不会报错，
            // 只会静默匹配不到任何文档——表现是「ES 一条结果都没有」。
            if (!searchProperties.isIncludeUnplayable()) {
                filter.add(term("copyrightStatus", 1));
            }
            addTerms(filter, "language", request.getLanguages());
            addTerms(filter, "genre", request.getGenres());
            addTerms(filter, "era", request.getEras());
            addDurations(filter, request.getDurations());
        }

        ObjectNode query = objectMapper.createObjectNode();
        query.set("bool", bool);
        body.set("query", query);
        body.set("sort", sort(request));
        ObjectNode highlight = body.putObject("highlight");
        ObjectNode nameField = highlight.putArray("fields").addObject();
        nameField.set("name", objectMapper.createObjectNode().put("number_of_fragments", 0));
        return body;
    }

    /**
     * 关键词查询。
     *
     * <p>关键词含中文时只有原文相关字段参与；关键词全 ASCII 时（{@link SearchRequest#hasPinyin()}）
     * 额外并上应用侧物化的拼音字段。拼音字段用 edge_ngram 建索引，
     * 所以 {@code zhou}、{@code zhoujielun}、{@code zjl} 都能命中「周杰伦」。
     *
     * <p>不能只靠 ES 的 multi-field `name.pinyin`：multi-field 是把**同一个值**交给另一个
     * 分析器，而 pinyin 插件未安装时没有任何分析器能把「周杰伦」变成 zhoujielun。
     */
    private ObjectNode keywordQuery(SearchRequest request) {
        ObjectNode original = multiMatch(request);
        if (!request.hasPinyin()) {
            return original;
        }
        ArrayNode should = objectMapper.createArrayNode();
        should.add(original);
        should.add(match("namePinyin", request.getPinyinKeyword(), 3));
        // D-46：首字母是 edge_ngram(1..20) 建的索引，单字符会命中「所有以该字母开头的歌」，
        // 噪声大到等于没筛。首字母这一项要求至少 2 个字符，与 MySQL 实现的守卫一致。
        boolean allowFirstLetters = request.getPinyinKeyword().length() >= 2;
        if (allowFirstLetters) {
            should.add(match("namePinyinFirst", request.getPinyinKeyword(), 4));
        }
        SearchRequest.Target target = request.getTarget();
        if (target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC
                || target == SearchRequest.Target.ALBUM) {
            should.add(match("artistNamePinyin", request.getPinyinKeyword(), 3));
            // 首字母（zjl → 周杰伦）比全拼更常用，但只在歌手字段上有意义：
            // 歌名首字母命中在 namePinyinFirst 里已经覆盖了
            if (allowFirstLetters) {
                should.add(match("artistNamePinyinFirst", request.getPinyinKeyword(), 4));
            }
        }
        if (target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC) {
            should.add(match("albumNamePinyin", request.getPinyinKeyword(), 2));
        }
        ObjectNode bool = objectMapper.createObjectNode();
        bool.set("should", should);
        bool.put("minimum_should_match", 1);
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("bool", bool);
        return wrapper;
    }

    private ObjectNode multiMatch(SearchRequest request) {
        ObjectNode match = objectMapper.createObjectNode();
        match.put("query", request.getKeyword());
        match.put("type", "best_fields");
        // 必选而不是默认的 OR：lite 映射用 cjk_bigram(含单字) 建索引，OR 下「晴天」会被拆成
        // 晴天/晴/天 三个词，于是任何只含「天」的歌（《平凡的一天》…）都会被召回——
        // 表现是搜「晴天」出十几条，而 MySQL 实现的 LIKE '%晴天%' 只有 1 条。
        // AND 要求同一个字段里同时出现这三个词，等价于「这些字都在」，单字查询仍然是单字匹配。
        match.put("operator", "and");
        ArrayNode fields = match.putArray("fields");
        SearchRequest.Target target = request.getTarget();
        if (target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC) {
            fields.add("name^5").add("artistName^3").add("albumName^2")
                    .add("tags^2").add("lyric^1");
        } else if (target == SearchRequest.Target.ARTIST) {
            fields.add("name^5").add("alias^2");
        } else if (target == SearchRequest.Target.ALBUM) {
            fields.add("name^5").add("artistName^2");
        } else {
            fields.add("name^5").add("tags^2");
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("multi_match", match);
        return wrapper;
    }

    /**
     * {@code match} 查询。
     *
     * <p>boost 要写在查询体里（{@code {"query":..., "boost":n}}），不能像 multi_match 的
     * fields 那样写成 {@code field^3}——后者会被当成一个不存在的字段名，静默不命中。
     */
    private ObjectNode match(String field, String value, double boost) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode detail = node.putObject("match").putObject(field);
        detail.put("query", value);
        detail.put("boost", boost);
        return node;
    }

    private ArrayNode sort(SearchRequest request) {
        ArrayNode sorts = objectMapper.createArrayNode();
        switch (request.getSort()) {
            case HOT:
                sorts.add(order("hotScore", "desc"));
                sorts.add(order("id", "desc"));
                break;
            case NEWEST:
                sorts.add(order("publishTime", "desc"));
                sorts.add(order("id", "desc"));
                break;
            case DURATION_ASC:
                sorts.add(order("duration", "asc"));
                sorts.add(order("id", "desc"));
                break;
            case DURATION_DESC:
                sorts.add(order("duration", "desc"));
                sorts.add(order("id", "desc"));
                break;
            case RELEVANCE:
            default:
                sorts.add(order("_score", "desc"));
                sorts.add(order("hotScore", "desc"));
                sorts.add(order("id", "desc"));
                break;
        }
        return sorts;
    }

    private ObjectNode order(String field, String direction) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject(field).put("order", direction);
        return node;
    }

    private ArrayNode searchAfter(SearchRequest request) {
        SearchCursor.Pos pos = SearchCursor.decode(request.getCursor());
        ArrayNode values = objectMapper.createArrayNode();
        switch (request.getSort()) {
            case HOT:
                values.add(pos.getHotScore()).add(pos.getId());
                break;
            case NEWEST:
                values.add(pos.getPublishTime() == null ? 0L
                        : pos.getPublishTime().toInstant(ZoneOffset.UTC).toEpochMilli());
                values.add(pos.getId());
                break;
            case DURATION_ASC:
            case DURATION_DESC:
                values.add(pos.getDuration() == null ? 0 : pos.getDuration()).add(pos.getId());
                break;
            case RELEVANCE:
            default:
                values.add(pos.getScore()).add(pos.getHotScore()).add(pos.getId());
                break;
        }
        return values;
    }

    private ObjectNode term(String field, Object value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("term").set(field, objectMapper.valueToTree(value));
        return node;
    }

    private void addTerms(ArrayNode filter, String field, java.util.Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ObjectNode terms = objectMapper.createObjectNode();
        terms.putObject("terms").set(field, objectMapper.valueToTree(values));
        filter.add(terms);
    }

    private void addDurations(ArrayNode filter, java.util.Set<String> durations) {
        if (durations == null || durations.isEmpty()) {
            return;
        }
        ArrayNode should = objectMapper.createArrayNode();
        for (String duration : durations) {
            ObjectNode range = objectMapper.createObjectNode();
            ObjectNode node = range.putObject("range").putObject("duration");
            if ("lt3".equals(duration)) {
                node.put("lt", 180);
            } else if ("3to5".equals(duration)) {
                node.put("gte", 180).put("lte", 300);
            } else if ("gt5".equals(duration)) {
                node.put("gt", 300);
            } else {
                // 未知取值判为不匹配，与 MySQL 实现的 otherwise 1=0 一致
                ObjectNode none = objectMapper.createObjectNode();
                none.putObject("term").put("id", -1L);
                should.add(none);
                continue;
            }
            should.add(range);
        }
        ObjectNode bool = objectMapper.createObjectNode();
        bool.set("should", should);
        bool.put("minimum_should_match", 1);
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("bool", bool);
        filter.add(wrapper);
    }

    /** 歌词检索走的就是歌曲文档（只是多一个 lyric 必须命中的条件），筛选口径与歌曲一致。 */
    private static boolean isSongLike(SearchRequest.Target target) {
        return target == SearchRequest.Target.SONG || target == SearchRequest.Target.LYRIC;
    }

    private String docType(SearchRequest.Target target) {
        switch (target) {
            case ARTIST:
                return "ARTIST";
            case ALBUM:
                return "ALBUM";
            case PLAYLIST:
                return "PLAYLIST";
            case SONG:
            case LYRIC:
            default:
                return "SONG";
        }
    }

    // ==================== 响应解析 ====================

    private SearchPage toPage(SearchRequest request, JsonNode response) {
        SearchPage page = new SearchPage();
        JsonNode hitsNode = response.path("hits");
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hitNode : hitsNode.path("hits")) {
            hits.add(toHit(request, hitNode));
        }
        page.setHits(hits);
        page.setHasMore(hits.size() >= request.getPageSize());
        if (!request.hasCursor()) {
            page.setTotal(hitsNode.path("total").path("value").asLong(0));
        }
        // 没有下一页就不给 cursor：前端「有 cursor 就显示下一页」是最省事的写法，
        // 给一个必然为空的 cursor 会让它多翻一页空数据（MySQL 实现也是这个口径）
        if (page.isHasMore() && !hits.isEmpty()) {
            SearchHit last = hits.get(hits.size() - 1);
            page.setNextCursor(SearchCursor.encode(last));
        }
        return page;
    }

    private SearchHit toHit(SearchRequest request, JsonNode node) {
        JsonNode source = node.path("_source");
        SearchHit hit = new SearchHit();
        hit.setId(longOf(source, "id"));
        hit.setTarget(request.getTarget());
        // 展示用取整分；游标要用精确浮点，否则 search_after 拿整数比浮点会越界（翻页重复）
        double rawScore = node.path("_score").asDouble(0);
        hit.setExactScore(rawScore);
        hit.setScore((int) Math.round(rawScore));
        hit.setName(source.path("name").asText(null));
        hit.setArtistId(longOf(source, "artistId"));
        hit.setArtistName(source.path("artistName").asText(null));
        hit.setAlbumId(longOf(source, "albumId"));
        hit.setAlbumName(source.path("albumName").asText(null));
        hit.setCover(source.path("cover").asText(null));
        hit.setLanguage(source.path("language").asText(null));
        hit.setGenre(source.path("genre").asText(null));
        hit.setEra(source.path("era").asText(null));
        hit.setDuration(intOf(source, "duration"));
        hit.setHotScore(intOf(source, "hotScore"));
        hit.setCopyrightStatus(intOf(source, "copyrightStatus"));
        hit.setSongCount(intOf(source, "songCount"));
        hit.setPlayCount(longOf(source, "playCount"));
        hit.setPublishTime(dateOf(source.path("publishTime").asText(null)));

        markMatchedFields(request, source, hit);
        return hit;
    }

    /**
     * 命中字段要按 `_source` 实际内容判断，不能「有拼音关键词就一律标 NAME_PINYIN」：
     * 搜 {@code zhoujielun} 命中「青花瓷」是因为**歌手**的拼音，前端应显示
     * 「命中歌手拼音」，而不是「命中歌名拼音」。
     */
    private void markMatchedFields(SearchRequest request, JsonNode source, SearchHit hit) {
        String keyword = request.getKeyword();
        if (containsIgnoreCase(hit.getName(), keyword)) {
            hit.mark(SearchHit.Field.NAME);
        }
        if (containsIgnoreCase(hit.getArtistName(), keyword)) {
            hit.mark(SearchHit.Field.ARTIST_NAME);
        }
        if (containsIgnoreCase(hit.getAlbumName(), keyword)) {
            hit.mark(SearchHit.Field.ALBUM_NAME);
        }

        if (request.hasPinyin()) {
            String pinyin = request.getPinyinKeyword();
            // 与 keywordQuery 的 D-46 守卫同一口径：单字符不参与首字母匹配，
            // 这里连「标记」也不给——否则查询虽然没查首字母字段，
            // 标记却会写一条 name.pinyin.first，验收脚本据此判断守卫失效。
            boolean allowFirstLetters = pinyin.length() >= 2;
            if (startsWithIgnoreCase(source.path("namePinyin").asText(null), pinyin)) {
                hit.mark(SearchHit.Field.NAME_PINYIN);
            }
            if (allowFirstLetters
                    && startsWithIgnoreCase(source.path("namePinyinFirst").asText(null), pinyin)) {
                hit.mark(SearchHit.Field.NAME_PINYIN_FIRST);
            }
            if (startsWithIgnoreCase(source.path("artistNamePinyin").asText(null), pinyin)
                    || (allowFirstLetters
                    && startsWithIgnoreCase(source.path("artistNamePinyinFirst").asText(null), pinyin))) {
                hit.mark(SearchHit.Field.ARTIST_NAME_PINYIN);
            }
        }
        // 歌词原文就在 _source 里（重建时已剥离 LRC）。命中判定与片段截取都在这里做：
        // MySQL 实现是把歌词切好片段再返回，ES 若把整篇歌词塞进 hit，等于把 LONGTEXT
        // 一路带进 VO 层，20 条命中就是几 MB。
        String lyric = source.path("lyric").asText(null);
        if (request.getTarget() == SearchRequest.Target.LYRIC) {
            hit.mark(SearchHit.Field.LYRIC);
        } else if (containsIgnoreCase(lyric, keyword)) {
            hit.mark(SearchHit.Field.LYRIC);
        }
        hit.setLyricFragment(lyricSnippet(lyric, keyword));
    }

    /** 命中窗口 = 关键词前后各 30 字，与 MySQL 实现的片段口径一致（D-36）。 */
    private static String lyricSnippet(String lyric, String keyword) {
        if (lyric == null || keyword == null || keyword.isEmpty()) {
            return null;
        }
        int index = lyric.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return null;
        }
        int from = Math.max(0, index - 30);
        int to = Math.min(lyric.length(), index + keyword.length() + 30);
        return lyric.substring(from, to);
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return text != null && keyword != null && !keyword.isEmpty()
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /** 拼音字段是 edge_ngram 建的索引，用户输入 `zhou` 应命中 `zhoujielun`。 */
    private static boolean startsWithIgnoreCase(String text, String keyword) {
        return text != null && keyword != null && !keyword.isEmpty()
                && text.toLowerCase(Locale.ROOT).startsWith(keyword.toLowerCase(Locale.ROOT));
    }

    private Long longOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() || value.isTextual() ? value.asLong() : null;
    }

    private Integer intOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() || value.isTextual() ? value.asInt() : null;
    }

    private LocalDateTime dateOf(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime();
            } catch (RuntimeException ignoredAgain) {
                try {
                    return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text)), ZoneOffset.UTC);
                } catch (RuntimeException ignoredThird) {
                    return null;
                }
            }
        }
    }
}
