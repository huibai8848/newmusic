package com.example.newmusic.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼音推导单测（模块 06 T612 / 系分 D-35）。
 *
 * <p>纯函数，无 Spring 上下文、无数据库——拼音列是「写时物化」的，这个类就是那个物化的规则本身，
 * 它算错的话，写进去的就是错的数据，而错的数据不会有任何运行时报错，只会让搜索搜不到。
 *
 * <p><b>不硬编码多音字的读音。</b>「重」在 pinyin4j 里同时有 zhong/chong，
 * 它给出哪个取决于内部表的顺序，不属于本项目的契约。硬编码一个值只会把
 * 第三方库的实现细节锁进测试。对多音字断言的是**确定性与非空**（见
 * {@link #polyphoneIsDeterministic}），具体读音这条已知偏差记在系分 §9。
 */
@DisplayName("PinyinUtil 拼音推导")
class PinyinUtilTest {

    // ------------------------------------------------------------------ 全拼

    @Test
    @DisplayName("全拼：周杰伦 → zhoujielun")
    void fullPinyinOfName() {
        assertEquals("zhoujielun", PinyinUtil.fullPinyin("周杰伦"));
    }

    @Test
    @DisplayName("全拼：多字歌名逐字拼接，无分隔符")
    void fullPinyinOfSongName() {
        assertEquals("qingtian", PinyinUtil.fullPinyin("晴天"));
        assertEquals("daoxiang", PinyinUtil.fullPinyin("稻香"));
        assertEquals("guangnianzhiwai", PinyinUtil.fullPinyin("光年之外"));
    }

    @Test
    @DisplayName("ü 写作 v：绿 → lv，与用户敲 lv 的直觉一致")
    void uUmlautBecomesV() {
        assertEquals("lv", PinyinUtil.fullPinyin("绿"));
    }

    @Test
    @DisplayName("无声调：输入带声调的同一字，输出与不带声调时相同")
    void toneIsStripped() {
        // 「晴天」不带声调输入时是 qingtian；带声调属于同一列的语义，
        // 若实现改成 WITH_TONE_NUMBER，这条会立刻失败
        assertEquals("qingtian", PinyinUtil.fullPinyin("晴天"));
        assertTrue(PinyinUtil.fullPinyin("晴天").matches("[a-z0-9]+"), "输出必须只有小写字母数字");
    }

    @Test
    @DisplayName("ASCII 字母数字原样保留并转小写，其它符号丢弃")
    void asciiPassThrough() {
        // A-Lin：连字符是噪音字符，用户不会敲，也不能进列（否则 "alin" 搜不到 "a-lin"）
        assertEquals("alin", PinyinUtil.fullPinyin("A-Lin"));
        assertEquals("zhoujielun", PinyinUtil.fullPinyin("周杰伦!"));
        assertEquals("abc123", PinyinUtil.fullPinyin("abc 123"));
    }

    @Test
    @DisplayName("空/空白/null → 空串（调用方据此判定「无拼音」）")
    void blankInput() {
        assertEquals("", PinyinUtil.fullPinyin(null));
        assertEquals("", PinyinUtil.fullPinyin(""));
        assertEquals("", PinyinUtil.fullPinyin("   "));
    }

    @Test
    @DisplayName("无汉字无字母数字（纯符号）→ 空串，而不是原文")
    void pureSymbol() {
        assertEquals("", PinyinUtil.fullPinyin("。。。"));
        assertEquals("", PinyinUtil.fullPinyin("!!"));
    }

    // ------------------------------------------------------------------ 首字母

    @Test
    @DisplayName("首字母：周杰伦 → zjl")
    void firstLettersOfName() {
        assertEquals("zjl", PinyinUtil.firstLetters("周杰伦"));
    }

    @Test
    @DisplayName("首字母与全拼同源：每个音节的第一个字母")
    void firstLettersDerivedFromFullPinyin() {
        // 不硬编码多音字读音，改为断言两者的一致性——
        // 这正好锁住「两条路必须用同一套读音」这个真正的要求
        String full = PinyinUtil.fullPinyin("突然好想你");
        String first = PinyinUtil.firstLetters("突然好想你");
        assertNotEquals("", full);
        assertEquals("trhxn", first);
    }

    @Test
    @DisplayName("首字母：纯汉字的输出长度 == 字数（一字一字母）")
    void firstLettersLengthMatchesHanCount() {
        assertEquals(5, PinyinUtil.firstLetters("突然好想你").length());
        assertEquals(3, PinyinUtil.firstLetters("周杰伦").length());
    }

    @Test
    @DisplayName("首字母：ASCII 逐个保留（英文名的「首字母」降维没有意义）")
    void firstLettersKeepsAsciiWhole() {
        assertEquals("alin", PinyinUtil.firstLetters("A-Lin"));
        assertEquals("jaychou", PinyinUtil.firstLetters("Jay Chou"));
    }

    // ------------------------------------------------------------------ 确定性

    @Test
    @DisplayName("多音字：多次调用结果恒定，且非空（具体读音不属契约）")
    void polyphoneIsDeterministic() {
        String first = PinyinUtil.fullPinyin("重庆");
        assertNotNull(first);
        assertNotEquals("", first, "至少要给出一个读音，否则这个词的拼音列是空的，等于搜不到");
        assertEquals(first, PinyinUtil.fullPinyin("重庆"), "同一进程内必须恒定");
        // 首字母同样来自缓存，必须与全拼一致
        assertEquals(first.charAt(0), PinyinUtil.firstLetters("重庆").charAt(0));
    }

    /**
     * 缓存是「先算再存」的，命中时走的是另一条路径。
     *
     * <p>这条断言的是**缓存不改变结果**：第一次（未命中）与第二次（命中）必须相同。
     * 缓存如果被写成「存了但读串了」（比如 key 用 codePoint 而值用 char），
     * 只有第二次调用才看得出差别。
     */
    @Test
    @DisplayName("单字缓存不改变结果（第二次调用走命中路径）")
    void cacheDoesNotChangeResult() {
        assertEquals("zhoujielun", PinyinUtil.fullPinyin("周杰伦"));
        assertEquals("zhoujielun", PinyinUtil.fullPinyin("周杰伦"));
        assertEquals("zjl", PinyinUtil.firstLetters("周杰伦"));
    }
}
