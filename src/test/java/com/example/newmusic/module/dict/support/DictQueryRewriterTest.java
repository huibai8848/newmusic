package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.service.DictSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询改写单测（模块 11 FR-20-042 / BR-20-017 / BR-20-054）。
 *
 * <p>这些规则决定「用户输入什么词去查库」。它们写错的症状很隐蔽：
 * 接口 200、有结果，只是结果变少或串味（例如把「稻香」裁成「稻」）。
 * 所以每条规则都用具体输入钉住，而不是只测「不抛异常」。
 */
@DisplayName("DictQueryRewriter 查询改写")
class DictQueryRewriterTest {

    private DictSnapshotService snapshot;
    private DictQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        snapshot = mock(DictSnapshotService.class);
        DictSnapshotService.QueryRules rules = new DictSnapshotService.QueryRules();
        // 快照服务会按长度降序排；这里保持同样顺序，先裁「我想听」再裁单字
        rules.getStopwords().addAll(Arrays.asList("我想听", "的", "歌"));
        rules.getSynonyms().put("周董", "周杰伦");
        rules.getSynonyms().put("rap", "说唱");
        rules.getPinyinAliases().put("chongqing", "zhongqing");
        when(snapshot.queryRules()).thenReturn(rules);
        rewriter = new DictQueryRewriter(snapshot);
    }

    @Test
    @DisplayName("原样关键词不改写，changed=false")
    void plainKeywordUnchanged() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("周杰伦");

        assertEquals("周杰伦", result.getKeyword());
        assertFalse(result.isChanged());
        assertFalse(result.isSynonymApplied());
        assertFalse(result.isStopwordTrimmed());
    }

    @Test
    @DisplayName("全角与多余空白归一化（BR-20-054）")
    void normalizeFullWidthAndWhitespace() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("　周杰伦　晴天 ");

        assertEquals("周杰伦 晴天", result.getKeyword());
        assertTrue(result.isChanged());
    }

    @Test
    @DisplayName("繁体转简体只影响检索词（BR-20-042）")
    void traditionalToSimple() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("陳奕迅");

        assertEquals("陈奕迅", result.getKeyword());
        assertTrue(result.isTraditionalConverted());
        assertTrue(result.isChanged());
    }

    @Test
    @DisplayName("整词同义词改写为主词")
    void synonymRewritesWholeKeyword() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("周董");

        assertEquals("周杰伦", result.getKeyword());
        assertTrue(result.isSynonymApplied());
    }

    @Test
    @DisplayName("多词查询不做同义词拼接")
    void synonymDoesNotApplyToMultiWordQuery() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("周董 稻香");

        assertEquals("周董 稻香", result.getKeyword());
        assertFalse(result.isSynonymApplied());
    }

    @Test
    @DisplayName("停用词只裁首尾，多轮裁到干净（BR-20-017）")
    void stopwordsTrimBothEdges() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("我想听周杰伦的歌");

        assertEquals("周杰伦", result.getKeyword());
        assertTrue(result.isStopwordTrimmed());
    }

    @Test
    @DisplayName("裁停用词不会把词裁到只剩 1 个字")
    void stopwordTrimKeepsAtLeastTwoChars() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("的确");

        assertEquals("的确", result.getKeyword());
        assertFalse(result.isStopwordTrimmed());
    }

    @Test
    @DisplayName("停用词不裁中间，完整歌名不被拆坏")
    void stopwordNeverTrimsMiddle() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("稻香");

        assertEquals("稻香", result.getKeyword());
        assertFalse(result.isStopwordTrimmed());
    }

    @Test
    @DisplayName("拼音别名：chongqing → zhongqing（多音字词典）")
    void pinyinAlias() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("chongqing");

        assertEquals("chongqing", result.getKeyword());
        assertEquals("zhongqing", result.getPinyinKeyword());
        assertTrue(result.isPinyinAlias());
    }

    @Test
    @DisplayName("拼音检索词去掉空格与标点")
    void pinyinKeywordNormalized() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("zhou jie-lun");

        assertEquals("zhoujielun", result.getPinyinKeyword());
        assertFalse(result.isPinyinAlias());
    }

    @Test
    @DisplayName("中文关键词不查拼音列（BR-15-014）")
    void chineseKeywordHasNoPinyin() {
        DictQueryRewriter.RewriteResult result = rewriter.rewrite("晴天");

        assertNull(result.getPinyinKeyword());
    }

    @Test
    @DisplayName("词典表缺失时按原文检索，搜索不 500")
    void dictionaryFailureFallsBackToRawKeyword() {
        when(snapshot.queryRules()).thenThrow(new IllegalStateException("table missing"));

        DictQueryRewriter.RewriteResult result = rewriter.rewrite("陳奕迅");

        assertEquals("陈奕迅", result.getKeyword());
        assertFalse(result.isSynonymApplied());
    }
}
