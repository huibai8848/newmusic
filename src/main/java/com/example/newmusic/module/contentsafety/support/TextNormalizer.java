package com.example.newmusic.module.contentsafety.support;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感词检测前的文本归一化（FR-27-001）。
 *
 * <p>不是「把文本洗干净」，而是「把各种绕过写法折叠成同一个可匹配形式」：
 * 全角转半角、繁体转简体、小写、去空白/标点/零宽字符。折叠后每个字符都保留
 * 它来自原文的哪个下标，命中位置才能映射回原文做高亮（BR-27-005）。
 *
 * <p>不做拼音化：拼音化会把所有汉字变成字母，误伤率不可控；PRD 里拼音变体
 * 由词库自行维护，属于运营策略而不是算法职责。
 *
 * <p>数字谐音也不做全局替换（会把手机号规则搅乱），词库直接收录数字变体即可。
 */
@Slf4j
public final class TextNormalizer {

    /** EX-27-001-3：超长文本截断，避免一次检测把线程拖住 */
    public static final int MAX_LENGTH = 10000;

    private static final Map<Character, String> SIMPLE_CACHE = new ConcurrentHashMap<>(256);
    private static volatile boolean converterUsable = true;

    private TextNormalizer() {
    }

    public static Result normalize(String text) {
        if (text == null || text.isEmpty()) {
            return new Result("", new int[0]);
        }
        String source = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        StringBuilder out = new StringBuilder(source.length());
        List<Integer> mapping = new ArrayList<>(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (isInvisible(c)) {
                continue;
            }
            c = halfWidth(c);
            char[] folded = foldChar(c);
            for (char f : folded) {
                if (f == '\u0000' || !isContentChar(f)) {
                    continue;
                }
                char lower = Character.toLowerCase(f);
                out.append(lower);
                mapping.add(i);
            }
        }
        int[] map = new int[mapping.size()];
        for (int i = 0; i < mapping.size(); i++) {
            map[i] = mapping.get(i);
        }
        return new Result(out.toString(), map);
    }

    /** 与 {@link #normalize(String)} 的文本形式一致，供词条入库/比对使用。 */
    public static String normalizeText(String text) {
        return normalize(text).getText();
    }

    private static char[] foldChar(char c) {
        char current = c;
        if (current >= '！' && current <= '～') {
            current = (char) (current - 0xFEE0);
        }
        if (current == '　') {
            return new char[0];
        }
        // 繁体 → 简体：按字符折叠。转换器通常返回 1 个字符，个别兼容字会展开，
        // 展开的每一段都映射回同一个原文下标，命中位置仍然正确。
        if (isCjk(current) && converterUsable) {
            String simple = SIMPLE_CACHE.get(current);
            if (simple == null) {
                try {
                    simple = ZhConverterUtil.toSimple(String.valueOf(current));
                } catch (Throwable e) {
                    converterUsable = false;
                    log.warn("繁简转换不可用，敏感词按原文匹配：{}", e.toString());
                    simple = String.valueOf(current);
                }
                SIMPLE_CACHE.put(current, simple);
            }
            return simple.toCharArray();
        }
        return new char[]{current};
    }

    private static boolean isContentChar(char c) {
        if (Character.isLetterOrDigit(c)) {
            return true;
        }
        int type = Character.getType(c);
        // 字母/数字之外的文字（如中文以外的组合符）保留；标点、符号一律去掉。
        return type == Character.OTHER_LETTER || type == Character.OTHER_NUMBER
                || type == Character.LETTER_NUMBER || type == Character.DECIMAL_DIGIT_NUMBER;
    }

    private static boolean isInvisible(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
            return true;
        }
        if (Character.isISOControl(c)) {
            return true;
        }
        // 零宽空格/连接符、BOM、软连字符、双向控制符、方向标记
        return c == '​' || c == '‌' || c == '‍' || c == '⁠'
                || c == '﻿' || c == '­'
                || (c >= '‎' && c <= '‏')
                || (c >= '‪' && c <= '‮')
                || (c >= '⁡' && c <= '⁤');
    }

    private static char halfWidth(char c) {
        if (c >= '！' && c <= '～') {
            return (char) (c - 0xFEE0);
        }
        if (c == '　') {
            return ' ';
        }
        return c;
    }

    private static boolean isCjk(char c) {
        return c >= '一' && c <= '鿿';
    }

    /** 归一化文本 + 「归一化下标 → 原文下标」映射。 */
    public static final class Result {
        private final String text;
        private final int[] mapping;

        Result(String text, int[] mapping) {
            this.text = text;
            this.mapping = mapping;
        }

        public String getText() {
            return text;
        }

        public int originalStart(int normalizedStart) {
            if (mapping.length == 0) {
                return 0;
            }
            int i = Math.max(0, Math.min(normalizedStart, mapping.length - 1));
            return mapping[i];
        }

        /** 半开区间 {@code [normalizedStart, normalizedEnd)} 映射回原文的半开区间。 */
        public int originalEnd(int normalizedEnd) {
            if (mapping.length == 0) {
                return 0;
            }
            if (normalizedEnd <= 0) {
                return 0;
            }
            int i = Math.min(normalizedEnd, mapping.length) - 1;
            return mapping[i] + 1;
        }
    }

    /** NFKC 仅用于需要兼容分解的测试/管理入口，不参与线上匹配主链路。 */
    public static String nfkc(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFKC);
    }
}
