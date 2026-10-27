package com.example.newmusic.module.search.support;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 搜索高亮（BR-15-005，系分 D-37）。
 *
 * <p>ES 会在响应里直接给出 {@code highlight} 片段，MySQL 不会，所以这件事只能在应用层做。
 *
 * <p><b>顺序不能反：先定位、再转义、后拼标签。</b>直觉写法是「先把整段文本转义，
 * 再把关键词替换成 {@code <em>词</em>}」，但那条路有个洞：用户搜 {@code &lt;} 时，
 * 文本里的 {@code <} 已经被转义成 {@code &lt;}，关键词却还是 {@code <}，于是替换不中——
 * 高亮静默失效。正确做法是在**原文**上找位置，切出来的每一段各自转义，标签由我们自己拼。
 *
 * <p>转义不是可选项：歌名、歌词都是用户可投稿内容。直接拼 {@code <em>} 进 HTML
 * 等于把存储型 XSS 送给前端，而前端拿到 {@code highlight} 就是往 {@code dangerouslySetInnerHTML}
 * 里塞的（那是高亮这个功能的唯一用法）。PRD 没写这一条，但它是「高亮」的必要条件。
 */
public final class HighlightSupport {

    /** BR-15-005 指定的标签，照抄 PRD，不要换成 {@code <b>} 或 {@code <mark>} */
    public static final String TAG_OPEN = "<em class=\"hl\">";
    public static final String TAG_CLOSE = "</em>";

    /** 歌词片段高亮的默认上下文半径（字符数） */
    private static final int DEFAULT_RADIUS = 30;

    private HighlightSupport() {
    }

    /**
     * 命中处包裹高亮标签，其余部分及其本身都做 HTML 转义。
     *
     * @param raw     字段原文（未转义）
     * @param keyword 用户输入的关键词；空白则整段转义、不加标签
     * @return 可直接交给前端的 HTML 片段；{@code raw} 为空时原样返回
     */
    public static String highlight(String raw, String keyword) {
        return highlight(raw, keyword, TAG_OPEN, TAG_CLOSE);
    }

    /** 同上，但可自定义标签（便于单测断言与将来换标签）。 */
    public static String highlight(String raw, String keyword, String open, String close) {
        if (raw == null) {
            return null;
        }
        if (!StringUtils.hasText(keyword)) {
            return escapeHtml(raw);
        }
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        int keywordLength = keyword.length();

        StringBuilder sb = new StringBuilder(raw.length() + 32);
        int cursor = 0;
        int hit;
        while ((hit = lowerRaw.indexOf(lowerKeyword, cursor)) >= 0) {
            sb.append(escapeHtml(raw.substring(cursor, hit)));
            sb.append(open)
                    .append(escapeHtml(raw.substring(hit, hit + keywordLength)))
                    .append(close);
            cursor = hit + keywordLength;
        }
        sb.append(escapeHtml(raw.substring(cursor)));
        return sb.toString();
    }

    /**
     * 从长文本里截取命中处周围的片段再高亮（歌词用）。
     *
     * <p>歌词是整篇 LRC 清洗后的正文，直接整段返回会让响应体大得离谱，
     * 前端也无法把它放进一行。截取窗口以**首个命中**为中心，两端各留
     * {@value #DEFAULT_RADIUS} 个字符，被截断的一侧补省略号。
     *
     * @return 未命中时返回 {@code null}（调用方据此判断「这个字段没命中」，
     *         而不是给一个没高亮的片段）
     */
    public static String snippet(String raw, String keyword) {
        return snippet(raw, keyword, DEFAULT_RADIUS);
    }

    public static String snippet(String raw, String keyword, int radius) {
        if (raw == null || !StringUtils.hasText(keyword)) {
            return null;
        }
        int hit = raw.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (hit < 0) {
            return null;
        }
        int start = Math.max(0, hit - radius);
        int end = Math.min(raw.length(), hit + keyword.length() + radius);
        // 换行在 LRC 清洗后仍然存在，片段里留着会把前端的一行撑成多行
        String window = raw.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + highlight(window, keyword) + (end < raw.length() ? "…" : "");
    }

    /**
     * HTML 转义。
     *
     * <p>覆盖 {@code & < > " '} 五个字符——双引号与单引号必须一起转，
     * 因为片段会被放进属性值时（如 {@code title="..."}）单靠转义尖括号挡不住。
     * 用自写的替换而不是 Hutool 的 {@code HtmlUtil.escape}：这里要精确控制
     * 转义集合与顺序（{@code &} 必须第一个换，否则会把后面产生的实体再转一遍）。
     */
    public static String escapeHtml(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
