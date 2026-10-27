package com.example.newmusic.module.search.engine;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深分页游标单测（模块 06 T612 / BR-15-010 / 系分 D-40）。
 *
 * <p>游标是**跨请求**传递的状态：编码它的那次请求与解码它的那次请求之间隔着一次网络往返、
 * 一次可能的发布、以及用户的任意编辑（游标就在 URL 上）。所以这里测的重点不是「能编能解」，
 * 而是两个失败方向：**被改坏时必须明确报错**（不能静默回到第一页），
 * 以及**可选字段缺失时不能把分段错位**（错位会让下一页从错误的位置开始，
 * 症状是「翻页时少了几条」这种极难复现的问题）。
 */
@DisplayName("SearchCursor 深分页游标")
class SearchCursorTest {

    // ------------------------------------------------------------------ 往返

    @Test
    @DisplayName("全字段往返：编解码后每个排序键都还原")
    void roundTripAllFields() {
        SearchCursor.Pos pos = pos(13, 980, LocalDateTime.of(2003, 7, 31, 10, 30, 0, 123_000_000), 269,
                1000000000000005001L);

        SearchCursor.Pos decoded = SearchCursor.decode(SearchCursor.encode(pos));

        assertEquals(13, decoded.getScore());
        assertEquals(980, decoded.getHotScore());
        assertEquals(pos.getPublishTime(), decoded.getPublishTime());
        assertEquals(Integer.valueOf(269), decoded.getDuration());
        assertEquals(1000000000000005001L, decoded.getId());
    }

    @Test
    @DisplayName("可选字段为 null 时往返仍是 5 段，不错位")
    void roundTripWithNullOptionalFields() {
        SearchCursor.Pos pos = pos(0, 0, null, null, 1000000000000005001L);

        SearchCursor.Pos decoded = SearchCursor.decode(SearchCursor.encode(pos));

        assertNull(decoded.getPublishTime(), "缺失的可选字段必须还原成 null，而不是被后一段顶上");
        assertNull(decoded.getDuration());
        assertEquals(1000000000000005001L, decoded.getId(), "id 是最后一段，最容易因错位而丢失");
    }

    @Test
    @DisplayName("整秒的 publishTime（toString 不带秒）也能解回来")
    void roundTripSecondPrecision() {
        LocalDateTime exact = LocalDateTime.of(2026, 10, 16, 10, 30, 0);
        SearchCursor.Pos decoded = SearchCursor.decode(SearchCursor.encode(pos(1, 1, exact, 60, 42L)));
        assertEquals(exact, decoded.getPublishTime());
    }

    @Test
    @DisplayName("从 SearchHit 编码：id 为 null 返回 null（调用方据此不写 nextCursor）")
    void encodeFromHit() {
        SearchHit hit = new SearchHit();
        hit.setId(1000000000000005001L);
        hit.setScore(13);
        assertNotNull(SearchCursor.encode(hit));

        assertNull(SearchCursor.encode((SearchHit) null));
        assertNull(SearchCursor.encode(new SearchHit()), "没有 id 的命中无法作为翻页锚点");
    }

    // ------------------------------------------------------------------ 不透明性

    @Test
    @DisplayName("编码是不透明的 base64url：不含 + / =，可以安全放进 URL")
    void urlSafe() {
        String cursor = SearchCursor.encode(pos(13, 980, LocalDateTime.now(), 269, 1000000000000005001L));
        // 「+」在 query string 里会被解成空格，「=」在部分框架里被当分隔符——
        // 两者都会让游标在传输中静默损坏
        assertFalse(cursor.contains("+"), "实际=" + cursor);
        assertFalse(cursor.contains("/"), "实际=" + cursor);
        assertFalse(cursor.contains("="), "实际=" + cursor);
        assertTrue(cursor.matches("[A-Za-z0-9_-]+"), "实际=" + cursor);
    }

    @Test
    @DisplayName("编码不直接暴露字段（游标的内部结构不属于接口契约）")
    void opaque() {
        String cursor = SearchCursor.encode(pos(13, 980, null, 269, 1000000000000005001L));
        assertFalse(cursor.contains("1000000000000005001"), "实际=" + cursor);
    }

    // ------------------------------------------------------------------ 非法输入

    @Test
    @DisplayName("不是 base64 → 10001，而不是静默当作首页")
    void garbageThrows() {
        BizException e = assertThrows(BizException.class,
                () -> SearchCursor.decode("这不是一个游标!!"));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("段数不对 → 10001（挡住「换了个版本的游标」）")
    void wrongPartCountThrows() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("13|980".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BizException e = assertThrows(BizException.class, () -> SearchCursor.decode(bad));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("段数对但内容不是数字 → 10001，且不抛 NumberFormatException")
    void nonNumericThrows() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("abc|980|||1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BizException e = assertThrows(BizException.class, () -> SearchCursor.decode(bad));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("空/null 游标 → 10001（调用方应先判空，而不是让这里返回一个空位置）")
    void blankThrows() {
        assertEquals(ResultCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> SearchCursor.decode(null)).getCode());
        assertEquals(ResultCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> SearchCursor.decode("  ")).getCode());
    }

    // ------------------------------------------------------------------ 辅助

    private static SearchCursor.Pos pos(int score, int hotScore, LocalDateTime publishTime,
                                       Integer duration, long id) {
        SearchCursor.Pos pos = new SearchCursor.Pos();
        pos.setScore(score);
        pos.setHotScore(hotScore);
        pos.setPublishTime(publishTime);
        pos.setDuration(duration);
        pos.setId(id);
        return pos;
    }
}
