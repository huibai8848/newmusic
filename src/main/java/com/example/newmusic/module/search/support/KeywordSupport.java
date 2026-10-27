package com.example.newmusic.module.search.support;

import org.springframework.util.StringUtils;

/**
 * 关键词的清洗与「值不值得展示」的判定。
 *
 * <p>热搜榜（BR-15-029）与联想（BR-15-018）都要求过滤掉单字符与纯符号，
 * 两处各写一遍的后果是规则会慢慢分叉——某天有人给热搜加了「长度 ≥ 2」
 * 而联想还是 ≥ 1，于是一个单字词在联想里出现、在榜上消失，排查时两边都「看着没错」。
 */
public final class KeywordSupport {

    /** 热搜/联想等**展示型**位置要求的最小长度。1 个字的词占了榜位却不提供信息 */
    public static final int MIN_DISPLAY_LENGTH = 2;

    private KeywordSupport() {
    }

    /**
     * 清洗：去首尾空白，把内部连续空白压成一个空格。
     *
     * <p>为什么要压内部空白：用户在输入框里多敲几个空格（或从别处粘贴）会产生
     * {@code "周杰伦  歌"} 这样的词，它与 {@code "周杰伦 歌"} 在热搜榜上是两条记录，
     * 但它们对用户是同一个词。压成单空格是**不做分词的前提下**能做到的最小归一。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * 是否值得展示。
     *
     * <p>三条：非空、长度够、至少含一个「实义字符」（汉字、字母或数字）。
     * 第三条是为了挡掉 {@code "。。"}、{@code "!!!"}、{@code "……"} 这类纯符号——
     * 它们长度够、也不是空白，但出现在热搜榜上没有任何意义，
     * 而它们恰恰是脚本刷榜最便宜的填充物。
     */
    public static boolean isDisplayable(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() < MIN_DISPLAY_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || isHan(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否含汉字。
     *
     * <p>用码点区间判而不是 {@code Character.UnicodeScript.of(c) == HAN}：
     * 后者在 JDK 8 上对扩展区（B~F 区，U+20000 以上）的代理对处理不一致，
     * 而这个项目跑在 JDK 8 上。这里覆盖基本区与扩展 A 区——够用且行为确定。
     */
    public static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
