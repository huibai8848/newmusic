package com.example.newmusic.module.admin.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台歌词保存请求的 Bean Validation 约束。
 *
 * <p>不启动 Spring，直接跑 Hibernate Validator：约束写在 DTO 上，
 * 这里验证的是「Controller 上的 {@code @Valid} 真的会拦下什么」。
 * 重点是 200000 字符上限与 content_type 取值域——它们对应的都是会写坏数据的输入，
 * 而不是界面上的可选校验。
 */
@DisplayName("SongLyricSaveReq 参数校验")
class SongLyricSaveReqValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("合法请求：LRC + 显式类型 / 纯文本 + 自动识别")
    void acceptsValidRequests() {
        SongLyricSaveReq lrc = req("[00:01.00]第一句", 1);
        assertTrue(validator.validate(lrc).isEmpty());

        SongLyricSaveReq plain = req("没有时间轴的歌词", null);
        assertTrue(validator.validate(plain).isEmpty());

        assertTrue(validator.validate(req("x", 2)).isEmpty());
        assertTrue(validator.validate(req("x", 3)).isEmpty());
    }

    @Test
    @DisplayName("正文为空/全空白/null 都拒绝")
    void rejectsBlankContent() {
        assertViolation(req(null, 1), "歌词内容不能为空");
        assertViolation(req("", 1), "歌词内容不能为空");
        assertViolation(req("   \n\t ", 1), "歌词内容不能为空");
    }

    @Test
    @DisplayName("正文上限 200000 字符，超一字符即拒绝")
    void rejectsTooLongContent() {
        assertTrue(validator.validate(req(repeat('a', 200000), 1)).isEmpty());
        assertViolation(req(repeat('a', 200001), 1), "歌词内容过长");
    }

    @Test
    @DisplayName("content_type 只允许 1/2/3；0 与 4 被拦下")
    void rejectsInvalidContentType() {
        assertEquals("歌词内容类型仅支持 1/2/3", firstMessage(req("歌词", 0)));
        assertEquals("歌词内容类型仅支持 1/2/3", firstMessage(req("歌词", 4)));
        assertEquals("歌词内容类型仅支持 1/2/3", firstMessage(req("歌词", -1)));
    }

    private SongLyricSaveReq req(String content, Integer contentType) {
        SongLyricSaveReq req = new SongLyricSaveReq();
        req.setContent(content);
        req.setContentType(contentType);
        return req;
    }

    private void assertViolation(SongLyricSaveReq req, String message) {
        assertEquals(message, firstMessage(req));
    }

    private String firstMessage(SongLyricSaveReq req) {
        Set<ConstraintViolation<SongLyricSaveReq>> violations = validator.validate(req);
        assertTrue(!violations.isEmpty(), "应当产生校验失败");
        return violations.stream().map(ConstraintViolation::getMessage)
                .collect(Collectors.toList()).get(0);
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, ch);
        return new String(chars);
    }
}
