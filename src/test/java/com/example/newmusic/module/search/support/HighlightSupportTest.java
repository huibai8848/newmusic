package com.example.newmusic.module.search.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索高亮单测（模块 06 T612 / BR-15-005 / 系分 D-37）。
 *
 * <p>两件事在这里被钉住：
 * <ol>
 *   <li><b>转义必须是转义。</b>高亮的输出会被前端塞进
 *       {@code dangerouslySetInnerHTML}（那是这个功能的唯一用法），
 *       所以歌名里的 {@code <script>} 必须变成 {@code &lt;script&gt;}。
 *       这是存储型 XSS 的唯一一道防线，也是本测试最重要的一组用例；</li>
 *   <li><b>「先定位再转义」这个顺序。</b>反过来写（先转义再找关键词）在
 *       搜 {@code <} 、{@code &} 这类字符时会静默失效，见
 *       {@link #keywordWithHtmlMetacharacter}。</li>
 * </ol>
 */
@DisplayName("HighlightSupport 高亮与转义")
class HighlightSupportTest {

    // ------------------------------------------------------------------ 基本高亮

    @Test
    @DisplayName("命中处包 <em class=\"hl\">，与 BR-15-005 的标签逐字一致")
    void highlightsHit() {
        assertEquals("<em class=\"hl\">晴天</em>", HighlightSupport.highlight("晴天", "晴天"));
        assertEquals("周杰伦 - <em class=\"hl\">晴天</em>",
                HighlightSupport.highlight("周杰伦 - 晴天", "晴天"));
    }

    @Test
    @DisplayName("全部命中都被包，不只是第一个")
    void highlightsEveryOccurrence() {
        assertEquals("<em class=\"hl\">a</em>b<em class=\"hl\">a</em>",
                HighlightSupport.highlight("aba", "a"));
    }

    @Test
    @DisplayName("大小写不敏感（用户敲 qingtian 要能高亮 QingTian）")
    void caseInsensitive() {
        assertEquals("<em class=\"hl\">QingTian</em>", HighlightSupport.highlight("QingTian", "qingtian"));
        assertEquals("<em class=\"hl\">qingtian</em>", HighlightSupport.highlight("qingtian", "QINGTIAN"));
    }

    @Test
    @DisplayName("未命中：原样返回（已转义），不加标签")
    void noHitNoTag() {
        assertEquals("稻香", HighlightSupport.highlight("稻香", "晴天"));
        assertFalse(HighlightSupport.highlight("稻香", "晴天").contains("<em"));
    }

    @Test
    @DisplayName("keyword 为空：整段转义但不加标签（不是返回原文）")
    void blankKeywordStillEscapes() {
        assertEquals("&lt;b&gt;", HighlightSupport.highlight("<b>", ""));
        assertEquals("&lt;b&gt;", HighlightSupport.highlight("<b>", null));
        assertEquals("&lt;b&gt;", HighlightSupport.highlight("<b>", "   "));
    }

    @Test
    @DisplayName("raw 为 null → null（调用方据此不写入 highlight）")
    void nullRaw() {
        assertNull(HighlightSupport.highlight(null, "晴天"));
        assertNull(HighlightSupport.escapeHtml(null));
    }

    // ------------------------------------------------------------------ XSS（最重要的一组）

    @Test
    @DisplayName("歌名里的 <script> 被转义，绝不原样进 HTML")
    void scriptTagIsEscaped() {
        String out = HighlightSupport.highlight("<script>alert(1)</script>", "晴天");
        assertFalse(out.contains("<script"), "输出里不能出现可执行的标签，实际=" + out);
        assertTrue(out.contains("&lt;script&gt;"), "尖括号必须转成实体，实际=" + out);
    }

    @Test
    @DisplayName("命中片段自身的尖括号也被转义（不能只转义未命中的部分）")
    void hitSegmentIsEscapedToo() {
        // 关键词就是要高亮的那个 <img> —— 标签我们加，内容仍然转义
        String out = HighlightSupport.highlight("<img src=x onerror=1>", "<img");
        assertFalse(out.contains("<img"), "命中的片段不能被原样吐出，实际=" + out);
        assertEquals("<em class=\"hl\">&lt;img</em> src=x onerror=1&gt;", out);
    }

    @Test
    @DisplayName("五个字符全部转义，且 & 先转（不产生二次实体）")
    void escapesAllFiveCharacters() {
        assertEquals("&amp;lt;", HighlightSupport.escapeHtml("&lt;"),
                "& 必须先换，否则会把后面生成的实体再转一遍");
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HighlightSupport.escapeHtml("&<>\"'"));
    }

    @Test
    @DisplayName("引号也转义：片段可能被放进 title=\"...\" 这类属性值里")
    void quotesAreEscaped() {
        String out = HighlightSupport.highlight("a\"b'c", "b");
        assertEquals("a&quot;<em class=\"hl\">b</em>&#39;c", out);
        // 断言「原文的两个引号都没了」比断言「输出里没有引号」更准：
        // 输出**必然**含引号——高亮标签本身是 <em class="hl">，那是我们自己写死的，
        // 不转义是安全的。原文的 " 在这里变成 &quot;、' 变成 &#39;，一个不剩
        assertFalse(out.contains("a\""), "原文的双引号必须被转义，实际=" + out);
        assertEquals(2, countOf(out, '"'), "剩下的两个引号只能来自标签的 class 属性，实际=" + out);
    }

    /**
     * 这条是「先定位、再转义」这个顺序的**唯一理由**。
     *
     * <p>若实现写成「先转义整段、再用原文关键词去替换」，那么用户搜 {@code <} 时，
     * 文本里的 {@code <} 已经变成 {@code &lt;}，关键词 {@code <} 找不到任何位置，
     * 高亮静默失效——不报错、不告警，只是那个词永远没有高亮。
     */
    @Test
    @DisplayName("关键词含 HTML 元字符时高亮仍然生效（先定位再转义的证据）")
    void keywordWithHtmlMetacharacter() {
        String out = HighlightSupport.highlight("a<b", "<");
        assertTrue(out.contains("<em class=\"hl\">"), "搜 < 必须能高亮，实际=" + out);
        assertEquals("a<em class=\"hl\">&lt;</em>b", out);

        String amp = HighlightSupport.highlight("Tom & Jerry", "&");
        assertEquals("Tom <em class=\"hl\">&amp;</em> Jerry", amp);
    }

    // ------------------------------------------------------------------ 歌词片段

    @Test
    @DisplayName("snippet：以首个命中为中心截取，两端补省略号")
    void snippetAroundHit() {
        String lyric = repeat('前', 100) + "故事的小黄花" + repeat('后', 100);
        String out = HighlightSupport.snippet(lyric, "小黄花", 10);
        assertTrue(out.startsWith("…"), "左侧被截断要有省略号，实际=" + out);
        assertTrue(out.endsWith("…"), "右侧被截断要有省略号，实际=" + out);
        assertTrue(out.contains("<em class=\"hl\">小黄花</em>"), "实际=" + out);
        assertTrue(out.length() < 60, "片段必须比原文短得多，实际长度=" + out.length());
    }

    @Test
    @DisplayName("snippet：未命中返回 null（调用方据此判定「这个字段没命中」）")
    void snippetMissReturnsNull() {
        assertNull(HighlightSupport.snippet("晴天", "稻香"));
        assertNull(HighlightSupport.snippet(null, "晴天"));
        assertNull(HighlightSupport.snippet("晴天", ""));
    }

    @Test
    @DisplayName("snippet：换行压成空格（片段要能放进一行）")
    void snippetCollapsesWhitespace() {
        String out = HighlightSupport.snippet("第一行\n第二行 晴天 第三行", "晴天", 30);
        assertFalse(out.contains("\n"), "换行会让前端的一行撑成多行，实际=" + out);
    }

    @Test
    @DisplayName("snippet：未截断的一侧不加省略号")
    void snippetShortTextHasNoEllipsis() {
        String out = HighlightSupport.snippet("晴天", "晴天", 30);
        assertEquals("<em class=\"hl\">晴天</em>", out);
    }

    // ------------------------------------------------------------------ 辅助

    private static String repeat(char c, int times) {
        char[] buf = new char[times];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }

    private static int countOf(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
