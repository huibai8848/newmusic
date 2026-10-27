package com.example.newmusic.module.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端 LRC 解析 / 重建（FR-23-002）。
 *
 * <p>前端已有一份 {@code parseLrc}，这里必须再写一份 Java 版：翻译结果要**落库**成
 * 带时间轴的 LRC，而落库发生在服务端。两份实现的规则必须一致，否则会出现
 * 「前端解析出 7 行、后端按 8 行校验」的错位。因此这里刻意对齐前端的三条规则：
 * 一行多个时间标签展开、毫秒 1~3 位右填充、逐字标签剥离。
 *
 * <p>只做行级时间轴。逐字歌词（contentType=2）的翻译按整行处理——把译文逐字对齐
 * 需要音素级时间戳，模型给不了，硬做只会产生错位的高亮。
 */
public final class LrcSupport {

    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD_TAG =
            Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    private static final Pattern NUMBERING = Pattern.compile("^\\s*\\d+\\s*[.、):]\\s*");
    /** 中日韩统一表意文字（含扩展 A 区） */
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]");

    private LrcSupport() {
    }

    public static List<LrcLine> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<LrcLine> lines = new ArrayList<>();
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            while (matcher.find()) {
                times.add(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
            if (times.isEmpty()) {
                // 元信息标签与纯文本行都不参与对齐
                continue;
            }
            String text = WORD_TAG.matcher(TIME_TAG.matcher(line).replaceAll("")).replaceAll("").trim();
            if (text.isEmpty()) {
                continue;
            }
            for (Long time : times) {
                lines.add(new LrcLine(time, text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.getTimeMs(), b.getTimeMs()));
        return lines;
    }

    /** 去重后的非空歌词行，顺序为首次出现顺序——送给模型的就是这一份。 */
    public static List<String> uniqueTexts(List<LrcLine> lines) {
        Set<String> texts = new LinkedHashSet<>();
        for (LrcLine line : lines) {
            if (line.getText() != null && !line.getText().trim().isEmpty()) {
                texts.add(line.getText().trim());
            }
        }
        return new ArrayList<>(texts);
    }

    /**
     * 用「原文 → 译文」映射重建带时间轴的 LRC。
     * 原文行没有对应译文时保留原文，避免生成缺行的翻译歌词。
     */
    public static String build(List<LrcLine> lines, Map<String, String> translations) {
        StringBuilder sb = new StringBuilder();
        for (LrcLine line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String translated = translations.get(line.getText());
            sb.append(format(line.getTimeMs()))
                    .append(translated == null || translated.trim().isEmpty() ? line.getText() : translated);
        }
        return sb.toString();
    }

    /** 把模型输出规整成「每行一条译文」，去掉行号与首尾空行。 */
    public static List<String> splitTranslations(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        for (String raw : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = NUMBERING.matcher(raw.trim()).replaceFirst("").trim();
            result.add(line);
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    public static Map<String, String> zip(List<String> originals, List<String> translations) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < originals.size(); i++) {
            map.put(originals.get(i), translations.get(i));
        }
        return map;
    }

    /** 中文占比超过一半即视为中文文本。用于 {@code language} 缺失时的兜底判断。 */
    public static boolean isMostlyChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int cjk = 0;
        int visible = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                visible++;
                if (CJK.matcher(new String(Character.toChars(cp))).matches()) {
                    cjk++;
                }
            }
            i += Character.charCount(cp);
        }
        return visible > 0 && cjk * 2 >= visible;
    }

    public static String format(long millis) {
        long safe = Math.max(0, millis);
        long minutes = safe / 60000;
        long seconds = (safe % 60000) / 1000;
        long ms = safe % 1000;
        return String.format("[%02d:%02d.%03d]", minutes, seconds, ms);
    }

    private static long toMillis(String minute, String second, String fraction) {
        long m = Long.parseLong(minute);
        long s = Long.parseLong(second);
        long ms = 0;
        if (fraction != null && !fraction.isEmpty()) {
            StringBuilder padded = new StringBuilder(fraction);
            while (padded.length() < 3) {
                padded.append('0');
            }
            ms = Long.parseLong(padded.toString());
        }
        return (m * 60 + s) * 1000 + ms;
    }

    /** 一行歌词（展开多个时间标签后的单条）。 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class LrcLine {
        private long timeMs;
        private String text;
    }
}
