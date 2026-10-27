package com.example.newmusic.module.search.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词清洗与「值不值得展示」单测（模块 06 T612 / BR-15-018）。
 *
 * <p>热搜榜（BR-15-029）与联想（BR-15-018）共用这一份规则。规则分叉的症状不是报错，
 * 而是「同一个词在联想里出现、在榜上消失」——两边单独看都没问题，
 * 所以这里逐条把规则本身钉住。
 */
@DisplayName("KeywordSupport 关键词清洗与展示判定")
class KeywordSupportTest {

    @Test
    @DisplayName("清洗：去首尾空白，内部连续空白压成一个空格")
    void normalize() {
        assertEquals("周杰伦 歌", KeywordSupport.normalize("  周杰伦   歌  "));
        assertEquals("晴天", KeywordSupport.normalize("晴天"));
        assertEquals("a b", KeywordSupport.normalize("a\t\nb"));
        assertNull(KeywordSupport.normalize(null));
    }

    @Test
    @DisplayName("清洗是幂等的（同一条词进两次榜单不会变成两条）")
    void normalizeIsIdempotent() {
        String once = KeywordSupport.normalize("  周杰伦   歌  ");
        assertEquals(once, KeywordSupport.normalize(once));
    }

    @Test
    @DisplayName("单字符不值得展示（占一个榜位但不提供信息）")
    void singleCharIsNotDisplayable() {
        assertFalse(KeywordSupport.isDisplayable("周"));
        assertFalse(KeywordSupport.isDisplayable("a"));
        assertFalse(KeywordSupport.isDisplayable("1"));
    }

    @Test
    @DisplayName("两字及以上且有实义字符 → 可展示")
    void normalKeywordIsDisplayable() {
        assertTrue(KeywordSupport.isDisplayable("晴天"));
        assertTrue(KeywordSupport.isDisplayable("zjl"));
        assertTrue(KeywordSupport.isDisplayable("ab"));
        assertTrue(KeywordSupport.isDisplayable("周杰伦 歌"));
    }

    @Test
    @DisplayName("纯符号不值得展示——它们长度够，却正是刷榜最便宜的填充物")
    void symbolOnlyIsNotDisplayable() {
        assertFalse(KeywordSupport.isDisplayable("。。"));
        assertFalse(KeywordSupport.isDisplayable("!!!"));
        assertFalse(KeywordSupport.isDisplayable("……"));
        assertFalse(KeywordSupport.isDisplayable("-- --"));
    }

    @Test
    @DisplayName("含至少一个实义字符即可（符号 + 汉字是正常的搜索词）")
    void needsAtLeastOneMeaningfulChar() {
        assertTrue(KeywordSupport.isDisplayable("《晴天》"));
        assertTrue(KeywordSupport.isDisplayable("周杰伦!!"));
    }

    @Test
    @DisplayName("空白 / null → 不可展示，且不抛异常")
    void blankIsNotDisplayable() {
        assertFalse(KeywordSupport.isDisplayable(null));
        assertFalse(KeywordSupport.isDisplayable(""));
        assertFalse(KeywordSupport.isDisplayable("   "));
    }

    @Test
    @DisplayName("汉字区间：基本区与扩展 A 区都算，拉丁字母不算")
    void isHan() {
        // 每个边界都给一条断言，且带可读的消息：区间判断写错一个端点时，
        // 报错消息会直接指出是哪个区间的哪一侧错的
        assertTrue(KeywordSupport.isHan('一'), "基本区首字 U+4E00");
        assertTrue(KeywordSupport.isHan('鿿'), "基本区末字 U+9FFF");
        assertTrue(KeywordSupport.isHan('㐀'), "扩展 A 区首字 U+3400");
        assertTrue(KeywordSupport.isHan('䶿'), "扩展 A 区末字 U+4DBF");
        assertFalse(KeywordSupport.isHan('㏿'), "扩展 A 区之前 U+33FF");
        assertFalse(KeywordSupport.isHan('ꀀ'), "基本区之后 U+A000");
        assertFalse(KeywordSupport.isHan('a'));
        assertFalse(KeywordSupport.isHan('1'));
        assertFalse(KeywordSupport.isHan('。'), "句号 U+3002");
        assertTrue(KeywordSupport.isHan('周'));
    }
}
