package com.example.newmusic.module.search.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索侧拼音规则单测（模块 06 T612 / BR-15-014）。
 *
 * <p>这里断言的不是「拼音算得对不对」（那是 {@code PinyinUtilTest}），而是
 * **什么时候拼音列参与检索**。{@link PinyinSupport#toSearchPinyin} 返回 {@code null}
 * 这件事是整个「含中文时不查拼音列」的实现方式：SQL 里 {@code LIKE CONCAT('%', NULL, '%')}
 * 恒不命中，所以 mapper 的 XML 里不需要写任何分支。null 如果退化成空串，
 * 那条 SQL 会变成 {@code LIKE '%%'}，**匹配全表**——这就是这条规则要单独测的原因。
 */
@DisplayName("PinyinSupport 检索侧拼音规则")
class PinyinSupportTest {

    // ------------------------------------------------------------------ 含中文 → null

    @Test
    @DisplayName("含中文的关键词 → null（拼音列不参与本次检索，BR-15-014）")
    void chineseKeywordDisablesPinyin() {
        assertNull(PinyinSupport.toSearchPinyin("晴天"));
        assertNull(PinyinSupport.toSearchPinyin("周杰伦"));
        // 中英混排也算「含中文」：拼音列存的是整个名字的拼音，
        // 「周jielun」这种半个拼音的输入本来就不该命中它
        assertNull(PinyinSupport.toSearchPinyin("周jielun"));
    }

    @Test
    @DisplayName("纯 ASCII 关键词 → 归一化后的拼音串")
    void asciiKeywordIsNormalized() {
        assertEquals("qingtian", PinyinSupport.toSearchPinyin("qingtian"));
        assertEquals("qingtian", PinyinSupport.toSearchPinyin("QingTian"));
        assertEquals("zjl", PinyinSupport.toSearchPinyin("zjl"));
        // 存的列是小写，输入必须转小写才可能 LIKE 上——大小写不转是最容易漏的一条
        assertEquals("qt", PinyinSupport.toSearchPinyin("QT"));
    }

    @Test
    @DisplayName("去空格与标点：zhou jie lun / zhou-jielun 都归一成 zhoujielun")
    void noiseCharactersAreStripped() {
        assertEquals("zhoujielun", PinyinSupport.toSearchPinyin("zhou jie lun"));
        assertEquals("zhoujielun", PinyinSupport.toSearchPinyin("zhou-jielun"));
        assertEquals("zhoujielun", PinyinSupport.toSearchPinyin("  zhou.jielun  "));
    }

    @Test
    @DisplayName("全是噪音字符 → null，不是空串（空串会让 LIKE 变成匹配全表的 '%%'）")
    void noiseOnlyIsNull() {
        assertNull(PinyinSupport.toSearchPinyin("- -"));
        assertNull(PinyinSupport.toSearchPinyin("..."));
        assertNull(PinyinSupport.toSearchPinyin("   "));
    }

    @Test
    @DisplayName("空/null 关键词 → null")
    void blankIsNull() {
        assertNull(PinyinSupport.toSearchPinyin(null));
        assertNull(PinyinSupport.toSearchPinyin(""));
    }

    // ------------------------------------------------------------------ 开关本身

    @Test
    @DisplayName("isAsciiOnly：只有全 ASCII 才为 true，空串为 false")
    void asciiOnly() {
        assertTrue(PinyinSupport.isAsciiOnly("abc123"));
        assertTrue(PinyinSupport.isAsciiOnly("zjl"));
        assertFalse(PinyinSupport.isAsciiOnly("晴天"));
        assertFalse(PinyinSupport.isAsciiOnly("jay周"));
        // 空串为 false 而不是 true：空关键词走的是「热搜 + 历史」那条路，
        // 把它归到「查拼音列」一侧会让调用方多一个需要特判的输入
        assertFalse(PinyinSupport.isAsciiOnly(""));
        assertFalse(PinyinSupport.isAsciiOnly(null));
    }

    @Test
    @DisplayName("全角字符也算非 ASCII（不能因为「看着像字母」就放进拼音列）")
    void fullWidthIsNotAscii() {
        assertNull(PinyinSupport.toSearchPinyin("ａｂｃ"));
        assertFalse(PinyinSupport.isAsciiOnly("ａｂｃ"));
    }
}
