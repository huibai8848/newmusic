package com.example.newmusic.module.contentsafety.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一化是「绕过写法折叠」的第一道门槛（FR-27-001）。
 *
 * <p>这里测的不是「文本变干净」，而是「不同的绕过写法落到同一个可匹配形式」，
 * 以及命中位置能映射回原文——后者决定高亮和脱敏快照是否指对位置。
 */
@DisplayName("TextNormalizer 文本归一化")
class TextNormalizerTest {

    @Test
    @DisplayName("全角字母数字折半角并转小写")
    void fullWidthFolded() {
        assertEquals("abc123", TextNormalizer.normalizeText("ＡＢＣ１２３"));
    }

    @Test
    @DisplayName("空白、零宽字符、标点全部剔除")
    void invisibleAndPunctuationRemoved() {
        String text = "加​微‌信，。！ 测试";
        assertEquals("加微信测试", TextNormalizer.normalizeText(text));
    }

    @Test
    @DisplayName("繁体折叠为简体（词库只维护简体即可）")
    void traditionalFolded() {
        assertEquals(TextNormalizer.normalizeText("赌博网站"), TextNormalizer.normalizeText("賭博網站"));
    }

    @Test
    @DisplayName("映射能把归一化位置还原到原文（含被剔除字符的偏移）")
    void mappingPointsBackToOriginal() {
        String text = "请 加 微 信 联系";
        TextNormalizer.Result result = TextNormalizer.normalize(text);
        assertEquals("请加微信联系", result.getText());
        int start = result.getText().indexOf("微信");
        int end = start + 2;
        assertEquals(text.indexOf("微"), result.originalStart(start));
        assertEquals(text.indexOf("信") + 1, result.originalEnd(end));
    }

    @Test
    @DisplayName("超长文本在 10000 字截断，避免拖死线程")
    void longTextTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TextNormalizer.MAX_LENGTH + 50; i++) {
            sb.append('a');
        }
        assertEquals(TextNormalizer.MAX_LENGTH, TextNormalizer.normalizeText(sb.toString()).length());
    }

    @Test
    @DisplayName("空输入安全返回空串")
    void nullAndEmptySafe() {
        assertEquals("", TextNormalizer.normalizeText(null));
        assertEquals("", TextNormalizer.normalizeText(""));
        assertTrue(TextNormalizer.normalize("").getText().isEmpty());
    }
}
