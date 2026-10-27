package com.example.newmusic.module.dict.support;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 词典文本的纯函数工具：归一化、繁简转换、词条校验、逐行解析。
 *
 * <p>放在 {@code support} 而不是 {@code util}：它只服务词典/搜索改写，
 * 曲库写路径不需要繁简转换。{@link com.example.newmusic.common.util.PinyinUtil} 仍留在
 * common，因为曲库与搜索都要用。
 */
@Slf4j
public final class DictTextSupport {

    /** 繁体转简体不可用时不阻断启动（EX-20-002-1 的降级口径） */
    private static volatile boolean converterUsable = true;

    private DictTextSupport() {
    }

    /**
     * 统一全角/半角并去掉首尾空白（BR-20-054）。
     *
     * <p>只做字符级归一化，不做繁简转换：繁简是带词典的可逆映射，
     * 与「全角逗号转半角」不是一类事情，混在一起会让日志难以解释。
     */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '　') {
                sb.append(' ');
            } else if (c >= '！' && c <= '～') {
                // 全角 ASCII 区：！(FF01) ~ ～(FF5E)
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** 繁体 → 简体。转换器缺失/异常时原样返回，保证搜索不中断。 */
    public static String toSimple(String text) {
        if (text == null || text.isEmpty() || !converterUsable) {
            return text;
        }
        try {
            return ZhConverterUtil.toSimple(text);
        } catch (Throwable e) {
            converterUsable = false;
            log.warn("繁简转换不可用，后续按原文检索：{}", e.toString());
            return text;
        }
    }

    /** 简体 → 繁体（索引侧可选；查询侧只用 toSimple）。 */
    public static String toTraditional(String text) {
        if (text == null || text.isEmpty() || !converterUsable) {
            return text;
        }
        try {
            return ZhConverterUtil.toTraditional(text);
        } catch (Throwable e) {
            converterUsable = false;
            log.warn("繁简转换不可用，后续按原文检索：{}", e.toString());
            return text;
        }
    }

    /**
     * 主词典词条校验（EX-20-001-1 / EX-20-001-3）。
     *
     * @return {@code null} 表示合法；否则返回可直接展示给运营的原因
     */
    public static String validateWord(String word, int minLength, int maxLength) {
        if (word == null || word.trim().isEmpty()) {
            return "词条不能为空";
        }
        String value = word.trim();
        if (!value.equals(word)) {
            return "词条首尾不能有空白";
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\t') >= 0 || value.indexOf(',') >= 0) {
            return "词条不能包含换行、制表符或英文逗号";
        }
        int length = value.codePointCount(0, value.length());
        if (length < minLength || length > maxLength) {
            return "词条长度需为 " + minLength + "~" + maxLength + " 个字符，当前 " + length + " 个";
        }
        return null;
    }

    /** 停用词/同义词的词条校验：允许 1 个字（"的"就是合法停用词），禁止分隔符。 */
    public static String validateToken(String word) {
        if (word == null || word.trim().isEmpty()) {
            return "词条不能为空";
        }
        String value = word.trim();
        if (!value.equals(word)) {
            return "词条首尾不能有空白";
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\t') >= 0 || value.indexOf(',') >= 0) {
            return "词条不能包含换行、制表符或英文逗号";
        }
        if (value.codePointCount(0, value.length()) > 64) {
            return "词条长度不能超过 64 个字符";
        }
        return null;
    }

    /** 拼音只允许小写字母（多音字词典是音节拼接，不含空格与声调）。 */
    public static String validatePinyin(String pinyin) {
        if (pinyin == null || pinyin.isEmpty()) {
            return "拼音不能为空";
        }
        for (int i = 0; i < pinyin.length(); i++) {
            char c = pinyin.charAt(i);
            if (c < 'a' || c > 'z') {
                return "拼音只能包含小写字母：" + pinyin;
            }
        }
        if (pinyin.length() > 64) {
            return "拼音长度不能超过 64 个字符";
        }
        return null;
    }

    /**
     * 拆行。导入文件支持 {@code #} 注释行与 BOM；空行跳过。
     * 返回的是**原始行号**（从 1 开始），错误报告需要它（BR-20-062）。
     */
    public static List<Line> lines(String content) {
        List<Line> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        String normalized = content.startsWith("﻿") ? content.substring(1) : content;
        String[] parts = normalized.split("\\r?\\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(new Line(i + 1, trimmed));
        }
        return result;
    }

    /** 同步日志里截断 message，避免一条异常把 500 列写满 */
    public static String brief(String message, int max) {
        if (message == null) {
            return null;
        }
        return message.length() <= max ? message : message.substring(0, max);
    }

    public static final class Line {
        private final int number;
        private final String text;

        Line(int number, String text) {
            this.number = number;
            this.text = text;
        }

        public int getNumber() {
            return number;
        }

        public String getText() {
            return text;
        }
    }
}
