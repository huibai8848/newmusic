package com.example.newmusic.module.search.engine;

import com.example.newmusic.module.search.config.SearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索引擎路由与降级单测（模块 06 T612 / EX-15-001-1 / AC-15-001-6 / AC-15-109）。
 *
 * <p><b>这组用例承担了一次真实的验收替代。</b>AC-15-001-6 原本要「停掉 ES，断言搜索仍可用」，
 * 但本机没有 ES 可停（系分 D-33）。把 ES 换成一对可用性可自由翻转的替身引擎之后，
 * 这条 AC 被拆成两个可执行的事实：「首选不可用时择到备选且 degraded=true」（本类）
 * 与「全部不可用时给出降级内容」（进程外，见 {@code scripts/verify-06.mjs}）。
 *
 * <p>这里断言的是**路由事实**而不是搜索结果：{@link SearchEngineRouter} 的职责边界
 * 恰好就是「选出哪个引擎 + 是否降级」，兜底内容该给什么是业务问题（归 SearchService）。
 */
@DisplayName("SearchEngineRouter 引擎选择与降级")
class SearchEngineRouterTest {

    // ------------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("首选可用 → 用它，且 degraded=false")
    void preferredAvailable() {
        SearchEngineRouter router = router("mysql", stub("mysql", true), stub("es", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertFalse(routed.isDegraded(), "用的就是首选，不该标记降级");
        assertFalse(routed.isNone());
    }

    // ------------------------------------------------------------------ 降级（AC-15-001-6）

    @Test
    @DisplayName("首选不可用 → 择到备选，且 degraded=true（AC-15-001-6）")
    void fallsBackAndMarksDegraded() {
        SearchEngineRouter router = router("es", stub("es", false), stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertTrue(routed.isDegraded(), "用的不是首选，必须能看出降级——这是 D-48 那个响应头的来源");
    }

    @Test
    @DisplayName("备选的可用性也要逐个探测：第一个备选挂了就继续往后找")
    void skipsUnavailableCandidates() {
        SearchEngineRouter router = router("es",
                stub("es", false), stub("solr", false), stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertTrue(routed.isDegraded());
    }

    @Test
    @DisplayName("首选排在注入顺序的最后也能被选中（按名字找，不是按位置）")
    void preferredFoundByNameNotPosition() {
        SearchEngineRouter router = router("mysql", stub("es", true), stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertFalse(routed.isDegraded(), "首选可用时不该因为它在列表后面就降级");
    }

    @Test
    @DisplayName("配置的首选引擎名没有对应实现 → 用第一个可用候选，且 degraded=true")
    void unknownPreferredNameFallsBack() {
        SearchEngineRouter router = router("elasticsearch", stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertTrue(routed.isDegraded(), "配错了引擎名，实际用的不是首选项，必须暴露出来");
    }

    @Test
    @DisplayName("引擎名大小写不敏感（配置里写 MySQL 不该让降级发生）")
    void preferredNameCaseInsensitive() {
        SearchEngineRouter router = router("MySQL", stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertFalse(routed.isDegraded());
        assertEquals("mysql", routed.getEngine().name());
    }

    // ------------------------------------------------------------------ 全部不可用

    @Test
    @DisplayName("全部不可用 → none()，engine 为 null，由调用方决定兜底内容")
    void allUnavailable() {
        SearchEngineRouter router = router("es", stub("es", false), stub("mysql", false));

        SearchEngineRouter.Routed routed = router.pick();

        assertTrue(routed.isNone());
        assertNull(routed.getEngine());
    }

    @Test
    @DisplayName("一个引擎都没有 → none()，不抛异常（搜索不可用，不是服务不可用）")
    void noEngines() {
        SearchEngineRouter router = new SearchEngineRouter(Collections.<SearchEngine>emptyList(), properties("mysql"));

        SearchEngineRouter.Routed routed = router.pick();

        assertTrue(routed.isNone());
    }

    /**
     * 探测方法自己抛异常，也必须算「不可用」。
     *
     * <p>反例是：ES 的连接池已坏，{@code isAvailable()} 里那句 ping 直接抛
     * {@code IllegalStateException}。此时若让它冒出去，整个搜索接口会 500——
     * 而正确行为恰恰相反：**探测失败是降级的信号，不是拒绝服务的理由**。
     */
    @Test
    @DisplayName("探测方法抛异常 → 算不可用并降级，不让异常冒出去（AC-15-109）")
    void probeThrowingIsTreatedAsUnavailable() {
        SearchEngineRouter router = router("es", throwing("es"), stub("mysql", true));

        SearchEngineRouter.Routed routed = router.pick();

        assertEquals("mysql", routed.getEngine().name());
        assertTrue(routed.isDegraded());
    }

    @Test
    @DisplayName("首选探测抛异常、备选也探测抛异常 → none()，异常不外泄")
    void allProbesThrowing() {
        SearchEngineRouter router = router("es", throwing("es"), throwing("mysql"));

        SearchEngineRouter.Routed routed = router.pick();

        assertTrue(routed.isNone());
    }

    // ------------------------------------------------------------------ 辅助

    private static SearchEngineRouter router(String preferred, SearchEngine... engines) {
        return new SearchEngineRouter(Arrays.asList(engines), properties(preferred));
    }

    private static SearchProperties properties(String preferred) {
        SearchProperties properties = new SearchProperties();
        properties.setEngine(preferred);
        return properties;
    }

    private static SearchEngine stub(String name, boolean available) {
        return new SearchEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public SearchPage search(SearchRequest request) {
                return SearchPage.empty();
            }
        };
    }

    private static SearchEngine throwing(String name) {
        return new SearchEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                throw new IllegalStateException("连接池已坏");
            }

            @Override
            public SearchPage search(SearchRequest request) {
                throw new IllegalStateException("连接池已坏");
            }
        };
    }
}
