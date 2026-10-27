package com.example.newmusic.module.search.engine.es;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.support.EsClient;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.engine.SearchCursor;
import com.example.newmusic.module.search.engine.SearchHit;
import com.example.newmusic.module.search.engine.SearchPage;
import com.example.newmusic.module.search.engine.SearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ES 查询 DSL 的单测（模块 11 / 模块 06）。
 *
 * <p>这里守的是「接真实 ES 才会暴露、单测不写就必然回归」的几件事：
 * <ul>
 *   <li>字段名必须与 mapping 一致（camelCase）。{@code dynamic:false} 下写错不会报错，
 *       只会静默匹配不到——表现是「ES 一条结果都没有」，很容易被误判成索引没数据；</li>
 *   <li>拼音 boost 必须写在 match 查询体里，不能写成 {@code field^3}（那是不存在的字段名）；</li>
 *   <li>命中字段要按 `_source` 判断，歌手拼音命中不能标成歌名拼音。</li>
 * </ul>
 */
@DisplayName("EsSearchEngine 查询构造与命中标记")
class EsSearchEngineTest {

    private static final String SONG_ID = "1000000000000020001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EsClient esClient;
    private EsSearchEngine engine;

    @BeforeEach
    void setUp() {
        esClient = mock(EsClient.class);
        DictProperties dictProperties = new DictProperties();
        dictProperties.getEs().setEnabled(true);
        dictProperties.getEs().setIndexAlias("music_song");
        SearchProperties searchProperties = new SearchProperties();
        engine = new EsSearchEngine(esClient, objectMapper, dictProperties, searchProperties);
    }

    @Test
    @DisplayName("过滤与排序使用 mapping 的 camelCase 字段")
    void usesCamelCaseMappingFields() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        engine.search(request("晴天", null, SearchRequest.Sort.NEWEST, null));

        JsonNode body = capturedBody();
        JsonNode filter = body.path("query").path("bool").path("filter");
        assertEquals(1, filter.get(1).path("term").path("copyrightStatus").asInt(),
                "过滤字段必须是 copyrightStatus：" + filter);
        assertFalse(filter.toString().contains("copyright_status"),
                "snake_case 字段在 dynamic=false 下会静默不命中：" + filter);
        assertTrue(body.path("sort").get(0).has("publishTime"),
                "排序字段必须是 publishTime：" + body.path("sort"));
        assertFalse(body.path("sort").toString().contains("publish_time"));
    }

    @Test
    @DisplayName("热度排序同样用 camelCase；写错的排序字段会让 ES 直接 400")
    void hotSortUsesCamelCaseField() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        engine.search(request("晴天", null, SearchRequest.Sort.HOT, null));

        String sort = capturedBody().path("sort").toString();
        assertTrue(sort.contains("\"hotScore\""), "热度排序字段必须是 hotScore：" + sort);
        assertFalse(sort.contains("hot_score"), "hot_score 不是 mapping 字段，排序会 400：" + sort);
    }

    @Test
    @DisplayName("歌手/专辑/歌单不套用歌曲的版权与筛选条件")
    void attachedTargetsSkipSongFilters() throws Exception {
        givenResponse("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");
        SearchRequest request = request("周杰伦", null, SearchRequest.Sort.RELEVANCE, null);
        request.setTarget(SearchRequest.Target.ARTIST);
        request.setLanguages(new java.util.HashSet<>(java.util.Arrays.asList("国语")));

        engine.search(request);

        String filter = capturedBody().path("query").path("bool").path("filter").toString();
        assertFalse(filter.contains("copyrightStatus"),
                "歌手文档没有 copyrightStatus，加了就恒不匹配（歌手 Tab 永远空）：" + filter);
        assertFalse(filter.contains("language"), "语种筛选只对歌曲有意义：" + filter);
        assertTrue(filter.contains("ARTIST"), filter);
    }

    @Test
    @DisplayName("全 ASCII 关键词并上拼音字段，boost 写在查询体里")
    void asciiKeywordAddsPinyinFields() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        engine.search(request("qingtian", "qingtian", SearchRequest.Sort.RELEVANCE, null));

        JsonNode should = capturedBody().path("query").path("bool").path("must")
                .get(0).path("bool").path("should");
        assertTrue(should.isArray() && should.size() >= 5, "应包含原文 + 拼音字段：" + should);
        assertTrue(should.toString().contains("\"namePinyin\""), should.toString());
        assertTrue(should.toString().contains("\"namePinyinFirst\""), should.toString());
        assertTrue(should.toString().contains("\"artistNamePinyin\""), should.toString());
        assertTrue(should.toString().contains("\"artistNamePinyinFirst\""),
                "歌手首字母（zjl）应能命中：" + should);
        assertTrue(should.toString().contains("\"albumNamePinyin\""), should.toString());
        for (JsonNode clause : should) {
            JsonNode match = clause.path("match");
            java.util.Iterator<String> fields = match.fieldNames();
            while (fields.hasNext()) {
                // multi_match 的 fields 里写 name^5 是对的；match 的字段名写 ^ 会静默不命中
                assertFalse(fields.next().contains("^"),
                        "match 字段名不能带 ^ boost：" + clause);
            }
            JsonNode boost = match.path("namePinyin").path("boost");
            if (!boost.isMissingNode()) {
                assertEquals(3, boost.asInt());
            }
        }
    }

    @Test
    @DisplayName("歌手拼音命中标记 ARTIST_NAME_PINYIN，而不是歌名拼音")
    void marksArtistPinyinHit() throws Exception {
        givenResponse(songResponse("青花瓷", "qinghuaci", "qhc", "zhoujielun"));

        SearchPage page = engine.search(request("zhoujielun", "zhoujielun",
                SearchRequest.Sort.RELEVANCE, null));

        SearchHit hit = page.getHits().get(0);
        assertTrue(hit.getMatchedFields().contains(SearchHit.Field.ARTIST_NAME_PINYIN),
                hit.getMatchedFields().toString());
        assertFalse(hit.getMatchedFields().contains(SearchHit.Field.NAME_PINYIN),
                "歌名拼音是 qinghuaci，不该被标成命中：" + hit.getMatchedFields());
    }

    @Test
    @DisplayName("歌手首字母 zjl 命中，同样标 ARTIST_NAME_PINYIN")
    void marksArtistFirstLettersHit() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        SearchPage page = engine.search(request("zjl", "zjl", SearchRequest.Sort.RELEVANCE, null));

        SearchHit hit = page.getHits().get(0);
        assertTrue(hit.getMatchedFields().contains(SearchHit.Field.ARTIST_NAME_PINYIN),
                hit.getMatchedFields().toString());
        assertFalse(hit.getMatchedFields().contains(SearchHit.Field.NAME_PINYIN_FIRST),
                "qt 不以 zjl 开头，不该标成歌名首字母命中：" + hit.getMatchedFields());
    }

    @Test
    @DisplayName("游标翻页用 search_after 且不查 total")
    void cursorUsesSearchAfter() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));
        SearchCursor.Pos pos = new SearchCursor.Pos();
        pos.setHotScore(100);
        pos.setPublishTime(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        pos.setId(Long.parseLong(SONG_ID));

        engine.search(request("晴天", null, SearchRequest.Sort.NEWEST, SearchCursor.encode(pos)));

        JsonNode body = capturedBody();
        assertFalse(body.path("track_total_hits").asBoolean(true), "游标翻页不应统计 total");
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                body.path("search_after").get(0).asLong());
        assertEquals(Long.parseLong(SONG_ID), body.path("search_after").get(1).asLong());
    }

    @Test
    @DisplayName("单字符拼音不参与首字母匹配，也不标记首字母命中（D-46）")
    void singleCharPinyinSkipsFirstLetters() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        SearchPage page = engine.search(request("q", "q", SearchRequest.Sort.RELEVANCE, null));

        JsonNode should = capturedBody().path("query").path("bool").path("must")
                .get(0).path("bool").path("should");
        assertFalse(should.toString().contains("namePinyinFirst"),
                "单字符不该查首字母字段（edge_ngram 1..20 会把所有以该字母开头的歌都召回）：" + should);
        assertFalse(should.toString().contains("artistNamePinyinFirst"),
                "歌手首字母同样要守卫：" + should);
        SearchHit hit = page.getHits().get(0);
        assertFalse(hit.getMatchedFields().contains(SearchHit.Field.NAME_PINYIN_FIRST),
                "查询没查首字母，标记也不能写：「q」前缀命中 qingtian 属于全拼项：" + hit.getMatchedFields());
    }

    @Test
    @DisplayName("中文关键词的 multi_match 用 operator=and，避免 cjk 单字把无关歌拉进来")
    void chineseKeywordRequiresAllTokens() throws Exception {
        givenResponse("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        engine.search(request("晴天", null, SearchRequest.Sort.RELEVANCE, null));

        JsonNode multiMatch = capturedBody().path("query").path("bool").path("must")
                .get(0).path("multi_match");
        assertEquals("and", multiMatch.path("operator").asText(),
                "lite 映射把「晴天」拆成 晴天/晴/天，OR 会命中所有只含「天」的歌：" + multiMatch);
    }

    @Test
    @DisplayName("不足一页时不给 cursor（否则前端会多翻一页空数据）")
    void noCursorWhenNotHasMore() throws Exception {
        givenResponse(songResponse("晴天", "qingtian", "qt", "zhoujielun"));

        SearchPage page = engine.search(request("晴天", null, SearchRequest.Sort.RELEVANCE, null));

        assertEquals(1, page.getHits().size());
        assertFalse(page.isHasMore());
        assertEquals(null, page.getNextCursor());
    }

    // ==================== 辅助 ====================

    private SearchRequest request(String keyword, String pinyin, SearchRequest.Sort sort, String cursor) {
        SearchRequest request = new SearchRequest();
        request.setKeyword(keyword);
        request.setPinyinKeyword(pinyin);
        request.setTarget(SearchRequest.Target.SONG);
        request.setSort(sort);
        request.setPageSize(10);
        request.setCursor(cursor);
        return request;
    }

    private void givenResponse(String json) throws Exception {
        when(esClient.search(eq("music_song"), any(JsonNode.class)))
                .thenReturn(objectMapper.readTree(json));
    }

    private JsonNode capturedBody() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(esClient).search(eq("music_song"), captor.capture());
        return captor.getValue();
    }

    private String songResponse(String name, String namePinyin, String namePinyinFirst,
                                String artistPinyin) {
        return "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{"
                + "\"_score\":4.2,"
                + "\"_source\":{"
                + "\"id\":" + SONG_ID + ","
                + "\"name\":\"" + name + "\","
                + "\"namePinyin\":\"" + namePinyin + "\","
                + "\"namePinyinFirst\":\"" + namePinyinFirst + "\","
                + "\"artistName\":\"周杰伦\","
                + "\"artistNamePinyin\":\"" + artistPinyin + "\","
                + "\"artistNamePinyinFirst\":\"zjl\","
                + "\"albumName\":\"叶惠美\","
                + "\"copyrightStatus\":1,"
                + "\"hotScore\":100,"
                + "\"duration\":269,"
                + "\"publishTime\":\"2024-01-01T00:00:00\""
                + "}}]}}";
    }
}
