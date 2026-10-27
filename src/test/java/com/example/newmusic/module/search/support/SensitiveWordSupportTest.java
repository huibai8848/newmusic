package com.example.newmusic.module.search.support;

import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.mapper.SearchConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤单测（模块 06 T612 / BR-15-002 / 系分 D-38）。
 *
 * <p><b>默认不拦任何词是设计，不是漏实现</b>（见 D-38）：词库属模块 18。
 * 这个类要证明的是「结构就位」——词典一填就能拦、两级来源的优先级、
 * 以及最要紧的一条：**读配置失败不能变成「拒绝服务」**。
 *
 * <p>用匿名实现代替 Mockito：{@code SearchConfigMapper} 只有一个方法，
 * 手写的替身比 mock 更能表达「这个替身会返回什么、会不会抛」。
 */
@DisplayName("SensitiveWordSupport 敏感词过滤")
class SensitiveWordSupportTest {

    // ------------------------------------------------------------------ 默认空词典

    @Test
    @DisplayName("默认（YAML 空 + 无 DB）不拦任何词——这是刻意的，见 D-38")
    void emptyDictionaryBlocksNothing() {
        SensitiveWordSupport support = support("", null);
        assertFalse(support.isBlocked("任何词"));
        assertFalse(support.isBlocked("fuck"));
        assertTrue(support.currentDictionary().isEmpty());
    }

    @Test
    @DisplayName("空关键词不拦（空关键词走「热搜 + 历史」那条路，不该被 70004 拦下）")
    void blankKeywordIsNotBlocked() {
        SensitiveWordSupport support = support("违禁词", null);
        assertFalse(support.isBlocked(null));
        assertFalse(support.isBlocked(""));
        assertFalse(support.isBlocked("   "));
    }

    // ------------------------------------------------------------------ YAML 词典

    @Test
    @DisplayName("YAML 词典命中 → 拦下")
    void yamlDictionaryBlocks() {
        SensitiveWordSupport support = support("违禁词,测试词", null);
        assertTrue(support.isBlocked("违禁词"));
        assertTrue(support.isBlocked("来搜违禁词吧"), "包含即命中");
        assertFalse(support.isBlocked("晴天"));
    }

    @Test
    @DisplayName("大小写不敏感（词典里存的小写，输入的大写也要拦）")
    void caseInsensitive() {
        SensitiveWordSupport support = support("FUCK", null);
        assertTrue(support.isBlocked("fuck"));
        assertTrue(support.isBlocked("FuCk"));
        assertEquals(1, support.currentDictionary().size());
    }

    @Test
    @DisplayName("三种分隔符：半角逗号、全角逗号、换行")
    void separators() {
        SensitiveWordSupport support = support("a词,b词，c词\nd词", null);
        assertEquals(4, support.currentDictionary().size(), "实际=" + support.currentDictionary());
        assertTrue(support.isBlocked("c词"));
        assertTrue(support.isBlocked("d词"));
    }

    @Test
    @DisplayName("空白项被丢弃（多余的逗号不该产生一个空词——空词会让每个关键词都命中）")
    void blankEntriesAreDropped() {
        SensitiveWordSupport support = support("违禁词, ,,", null);
        assertEquals(1, support.currentDictionary().size(), "实际=" + support.currentDictionary());
        assertFalse(support.isBlocked("晴天"), "空的词典项如果留下来，contains(\"\") 恒为 true");
    }

    // ------------------------------------------------------------------ DB 覆盖 YAML

    @Test
    @DisplayName("DB 有值 → 覆盖 YAML（运营改配置即刻生效是 BR-15-002 的要求）")
    void databaseOverridesYaml() {
        SensitiveWordSupport support = support("yaml词", "db词");
        assertTrue(support.isBlocked("db词"));
        assertFalse(support.isBlocked("yaml词"), "DB 有值时 YAML 不再参与");
    }

    @Test
    @DisplayName("DB 为空/空白 → 用 YAML")
    void blankDatabaseFallsBackToYaml() {
        assertTrue(support("yaml词", null).isBlocked("yaml词"));
        assertTrue(support("yaml词", "").isBlocked("yaml词"));
        assertTrue(support("yaml词", "   ").isBlocked("yaml词"));
    }

    @Test
    @DisplayName("读 DB 抛异常 → 退回 YAML，不抛异常（词典读不到是「少一层保护」，不是「拒绝服务」）")
    void databaseFailureFallsBackToYaml() {
        SensitiveWordSupport support = new SensitiveWordSupport(properties("yaml词"), key -> {
            throw new IllegalStateException("t_sys_config 挂了");
        });
        assertTrue(support.isBlocked("yaml词"), "库挂了仍应使用 YAML 词典");
        assertFalse(support.isBlocked("晴天"));
    }

    @Test
    @DisplayName("无 DB 依赖（null mapper）→ 只用 YAML")
    void nullMapperUsesYamlOnly() {
        SensitiveWordSupport support = new SensitiveWordSupport(properties("yaml词"), null);
        assertTrue(support.isBlocked("yaml词"));
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 5 秒刷新窗口是「词典变更能被下一个请求看到」与「不能每个搜索请求都打一次库」之间的折中。
     *
     * <p>{@code invalidate()} 是这个窗口的逃生门（也是 AC-15-003-3 能在进程外实测的前提）：
     * 运营改完配置后不必等 5 秒。这条测的是「刷新真的重新读了库」，
     * 而不是「缓存住了所以永远返回旧值」。
     */
    @Test
    @DisplayName("invalidate() 后重新读 DB，改词典即刻生效")
    void invalidateReloadsFromDatabase() {
        AtomicReference<String> dbValue = new AtomicReference<>("第一版词");
        SearchConfigMapper mapper = key -> dbValue.get();
        SensitiveWordSupport support = new SensitiveWordSupport(properties(""), mapper);

        assertTrue(support.isBlocked("第一版词"));
        assertFalse(support.isBlocked("第二版词"));

        dbValue.set("第二版词");
        support.invalidate();

        assertTrue(support.isBlocked("第二版词"), "invalidate 之后必须重新读库");
    }

    @Test
    @DisplayName("刷新窗口内不重复读库（否则每个搜索请求都会打一次 t_sys_config）")
    void readIsCachedWithinWindow() {
        int[] calls = {0};
        SearchConfigMapper mapper = key -> {
            calls[0]++;
            return "词";
        };
        SensitiveWordSupport support = new SensitiveWordSupport(properties(""), mapper);

        for (int i = 0; i < 20; i++) {
            support.isBlocked("词");
        }
        assertEquals(1, calls[0], "20 次判定只应读一次库，实际读了 " + calls[0] + " 次");
    }

    // ------------------------------------------------------------------ 辅助

    private static SensitiveWordSupport support(String yamlWords, String dbValue) {
        SearchConfigMapper mapper = dbValue == null ? null : key -> dbValue;
        return new SensitiveWordSupport(properties(yamlWords), mapper);
    }

    private static SearchProperties properties(String words) {
        SearchProperties properties = new SearchProperties();
        properties.getSensitive().setWords(words);
        return properties;
    }
}
