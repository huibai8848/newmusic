package com.example.newmusic.module.search.engine;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 深分页游标（BR-15-010，系分 D-40）。
 *
 * <p>{@code from + size > 1000} 时必须改用游标：{@code LIMIT 9900, 100} 要让存储引擎
 * 先数过 9900 行再丢掉，越翻越慢，而游标是「从上一页最后一行的排序键继续」，
 * 代价恒定。这也是 ES {@code search_after} 的语义，换引擎时这一层不用改。
 *
 * <p><b>为什么把四个排序键全带上</b>，而不是只带当前排序用得到的那个：
 * 排序是请求参数，用户完全可以在第 3 页把「相关性」切成「最新」。
 * 游标若只带 {@code score}，切换排序后就成了一个语义不明的串。带上全部键，
 * 解码方按当前排序取用即可，且**任何排序下的游标都是同一种格式**。
 *
 * <p>编码是 {@code base64url(score|hotScore|publishTime|duration|id)}。用不透明串
 * 而不是把字段摊在 URL 上，是为了让「游标的内部结构」不属于接口契约——
 * 将来加一个排序键不必改接口文档。
 *
 * <p>{@code score} 允许是小数：MySQL 的分是整数（加权命中数），ES 的 {@code _score}
 * 是 BM25 浮点。截断成整数再传给 {@code search_after} 会让「同分的两页互相越界」，
 * 翻页永远停在前几条。整数分仍然编码成 {@code 6} 而不是 {@code 6.0}，
 * 老游标（整数段）与 MySQL 的行值比较都不受影响。
 */
public final class SearchCursor {

    /** 按键的固定顺序编码，改顺序等于让线上已发出的游标全部失效 */
    private static final String SEPARATOR = "|";
    private static final int PART_COUNT = 5;

    private SearchCursor() {
    }

    /** 一页数据的排序键快照。{@code id} 恒有值，其余按排序类型可能为 null。 */
    @Data
    public static class Pos {
        private double score;
        private int hotScore;
        private LocalDateTime publishTime;
        private Integer duration;
        private long id;
    }

    public static String encode(SearchHit hit) {
        if (hit == null || hit.getId() == null) {
            return null;
        }
        Pos pos = new Pos();
        // ES 命中带精确 _score；MySQL 命中没有（exactScore 默认 0），回落到整数分
        pos.setScore(hit.getExactScore() != 0d ? hit.getExactScore() : hit.getScore());
        pos.setHotScore(hit.getHotScore() == null ? 0 : hit.getHotScore());
        pos.setPublishTime(hit.getPublishTime());
        pos.setDuration(hit.getDuration());
        pos.setId(hit.getId());
        return encode(pos);
    }

    public static String encode(Pos pos) {
        StringBuilder raw = new StringBuilder(64);
        raw.append(formatScore(pos.getScore())).append(SEPARATOR)
                .append(pos.getHotScore()).append(SEPARATOR)
                .append(pos.getPublishTime() == null ? "" : pos.getPublishTime().toString()).append(SEPARATOR)
                .append(pos.getDuration() == null ? "" : pos.getDuration()).append(SEPARATOR)
                .append(pos.getId());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 整数分写成 {@code 6}（不是科学计数法的 {@code 6.0E0}，也不是 {@code 6.0}） */
    private static String formatScore(double score) {
        return java.math.BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
    }

    /**
     * 解码。
     *
     * <p>非法游标抛 {@code 10001} 而不是静默当作首页：把「上一页的游标被改坏了」
     * 和「从头开始」混为一谈，会让前端在翻页时莫名其妙地跳回第一页且毫无提示。
     * 明确的参数错误是这里唯一正确的反馈。
     */
    public static Pos decode(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            throw new BizException(ResultCode.PARAM_INVALID, "cursor 不能为空");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "cursor 不是合法的游标");
        }
        String[] parts = raw.split("\\" + SEPARATOR, -1);
        if (parts.length != PART_COUNT) {
            throw new BizException(ResultCode.PARAM_INVALID, "cursor 不是合法的游标");
        }
        try {
            Pos pos = new Pos();
            pos.setScore(Double.parseDouble(parts[0]));
            pos.setHotScore(Integer.parseInt(parts[1]));
            pos.setPublishTime(parts[2].isEmpty() ? null : LocalDateTime.parse(parts[2]));
            pos.setDuration(parts[3].isEmpty() ? null : Integer.valueOf(parts[3]));
            pos.setId(Long.parseLong(parts[4]));
            return pos;
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "cursor 不是合法的游标");
        }
    }
}
