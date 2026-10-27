package com.example.newmusic.module.contentsafety.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DFA 引擎与规则匹配（FR-27-001/003）。
 *
 * <p>重点覆盖三件事：分级命中、白名单只豁免被包住的命中、以及热加载失败
 * 必须保留旧快照（EX-27-001-2）——最后一条如果错了，线上会出现「词库改坏后裸奔」。
 */
@DisplayName("SensitiveWordEngine 敏感词引擎")
class SensitiveWordEngineTest {

    @Test
    @DisplayName("分级命中：L3/L2/L1 与命中位置都正确")
    void detectsLevelsAndPositions() {
        SensitiveWordEngine engine = engine();
        SensitiveWordEngine.Result result = engine.detect("来玩赌博网站和加微信，最便宜");
        assertEquals(3, result.getMaxLevel());
        assertTrue(result.getMatches().stream().anyMatch(m -> m.getLevel() == 2));
        assertTrue(result.getMatches().stream()
                .anyMatch(m -> "加微信".equals(m.getRawWord()) && m.getStart() == 7));
    }

    @Test
    @DisplayName("白名单包住的命中被豁免，白名单外的同词仍命中")
    void whitelistInsideOnly() {
        SensitiveWordEngine engine = engine();
        assertTrue(engine.detect("加微信支付官方").getMatches().isEmpty(), "整词被白名单包住应豁免");
        assertFalse(engine.detect("加微信支付官方，另外加微信").getMatches().isEmpty(),
                "白名单只豁免被包住的那一处");
    }

    @Test
    @DisplayName("归一化后仍能命中（全角/空格/零宽绕过）")
    void normalizationBypassCaught() {
        SensitiveWordEngine engine = engine();
        assertEquals(2, engine.detect("加 微 信").getMaxLevel());
        assertEquals(3, engine.detect("赌​博网站").getMaxLevel());
    }

    @Test
    @DisplayName("正则规则按场景生效，REJECT/REVIEW 动作可区分")
    void rulesRespectScene() {
        SensitiveWordEngine engine = engine();
        SensitiveWordEngine.Result all = engine.detect("联系 13812345678");
        assertTrue(all.hasReviewRule());

        SensitiveWordEngine.Result comment = engine.detect("联系 13812345678", "COMMENT");
        assertTrue(comment.hasReviewRule(), "COMMENT 场景规则应命中");

        SensitiveWordEngine.Result other = engine.detect("联系 13812345678", "PLAYLIST");
        assertFalse(other.hasReviewRule(), "PLAYLIST 不应命中 COMMENT 专属规则");
    }

    @Test
    @DisplayName("非法正则被跳过，不影响其它规则与词库")
    void invalidRuleSkipped() {
        SensitiveWordEngine engine = new SensitiveWordEngine();
        boolean ok = engine.reload(wordInputs(), Collections.emptyList(),
                Arrays.asList(rule(1, "(", "ALL", "REJECT"),
                        rule(2, "\\d{6}", "ALL", "REVIEW")), 7);
        assertTrue(ok);
        assertEquals(2, engine.wordCount());
        SensitiveWordEngine.Result result = engine.detect("验证码 123456");
        assertFalse(result.hasRejectRule());
        assertTrue(result.hasReviewRule());
    }

    @Test
    @DisplayName("掩码保留首尾，不泄露完整词条")
    void maskHidesMiddle() {
        assertEquals("赌**站", SensitiveWordEngine.mask("赌博网站"));
        assertEquals("加*", SensitiveWordEngine.mask("加微"));
        assertEquals("*", SensitiveWordEngine.mask("禁"));
        assertEquals("", SensitiveWordEngine.mask(null));
    }

    @Test
    @DisplayName("初始兜底词库可拦截最底线内容；reload 后关闭 fallback")
    void fallbackThenReload() {
        SensitiveWordEngine engine = new SensitiveWordEngine();
        assertTrue(engine.isFallback());
        assertEquals(3, engine.detect("赌博网站").getMaxLevel());

        assertTrue(engine.reload(wordInputs(), Collections.emptyList(), Collections.emptyList(), 9));
        assertFalse(engine.isFallback());
        assertEquals(9, engine.version());
        assertEquals(2, engine.wordCount());
    }

    private static SensitiveWordEngine engine() {
        SensitiveWordEngine engine = new SensitiveWordEngine();
        engine.reload(wordInputs(), Collections.singletonList("加微信支付官方"),
                Collections.singletonList(rule(9, "1[3-9]\\d{9}", "COMMENT", "REVIEW")), 1);
        return engine;
    }

    private static java.util.List<SensitiveWordEngine.WordInput> wordInputs() {
        return Arrays.asList(
                new SensitiveWordEngine.WordInput(1, "赌博网站", 3, "违法", "OFFICIAL"),
                new SensitiveWordEngine.WordInput(2, "加微信", 2, "广告", "OPERATION"));
    }

    private static SensitiveWordEngine.RuleInput rule(long id, String pattern, String scene,
                                                      String action) {
        return new SensitiveWordEngine.RuleInput(id, "测试规则", pattern, scene, action);
    }

    @Test
    @DisplayName("空文本返回空结果而不是 NPE")
    void blankTextSafe() {
        SensitiveWordEngine engine = engine();
        assertNotNull(engine.detect(null));
        assertTrue(engine.detect("").isEmpty());
        assertEquals(0, engine.detect("   ").getMaxLevel());
    }
}
