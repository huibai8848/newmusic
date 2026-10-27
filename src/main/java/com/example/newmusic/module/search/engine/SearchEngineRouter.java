package com.example.newmusic.module.search.engine;

import com.example.newmusic.module.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引擎选择与降级（EX-15-001-1 / EX-15-013，系分 D-33）。
 *
 * <p>把「降级」从一句形容词变成一个**可观测的事实**：{@link Routed#isDegraded()}
 * 会被写进响应体的 {@code degraded} 字段与同名响应头（D-48）。只说「降级了」而没有
 * 一个能被断言的开关，运维就只能在用户投诉之后才知道 ES 挂了。
 *
 * <p>构造参数是 {@code List<SearchEngine>}：Spring 会注入所有实现，
 * 顺序由 {@code @Order} 决定。加一个 {@code EsSearchEngine} 就自动多一个候选，
 * **这个类不需要改**。
 */
@Slf4j
@Component
public class SearchEngineRouter {

    private final List<SearchEngine> engines;
    private final String preferredName;

    public SearchEngineRouter(List<SearchEngine> engines, SearchProperties properties) {
        this.engines = engines;
        this.preferredName = properties.getEngine();
        log.info("搜索引擎路由就绪：首选={} 候选={}", preferredName, engines.size());
    }

    /**
     * 择一个可用引擎。
     *
     * <p>先试首选；首选不可用则按注入顺序试其余，此时 {@code degraded = true}。
     * 全部不可用返回 {@link Routed#none()}——由调用方决定兜底内容，
     * 因为「兜底该给什么」是业务问题（热门歌曲 + 提示），不是路由问题。
     */
    public Routed pick() {
        if (engines.isEmpty()) {
            log.error("没有任何 SearchEngine 实现，搜索不可用");
            return Routed.none();
        }
        SearchEngine preferred = findPreferred();
        if (preferred != null && isAvailable(preferred)) {
            return new Routed(preferred, false);
        }
        if (preferred != null) {
            log.warn("首选搜索引擎不可用，尝试降级 name={}", preferred.name());
        }
        for (SearchEngine engine : engines) {
            if (engine == preferred) {
                continue;
            }
            if (isAvailable(engine)) {
                log.warn("搜索已降级：{} → {}（响应将标记 degraded=true）", preferredName, engine.name());
                return new Routed(engine, true);
            }
        }
        log.error("全部搜索引擎不可用，候选数={}", engines.size());
        return Routed.none();
    }

    private SearchEngine findPreferred() {
        for (SearchEngine engine : engines) {
            if (preferredName != null && preferredName.equalsIgnoreCase(engine.name())) {
                return engine;
            }
        }
        log.warn("配置的首选引擎 {} 没有对应实现，改用第一个候选", preferredName);
        return null;
    }

    /** {@code isAvailable()} 自身抛异常也算不可用——一个探测方法把自己弄崩了，不能连累整个搜索 */
    private boolean isAvailable(SearchEngine engine) {
        try {
            return engine.isAvailable();
        } catch (Exception e) {
            log.warn("引擎可用性探测失败 name={} cause={}", engine.name(), e.toString());
            return false;
        }
    }

    /** 择引擎的结果：{@code engine == null} 表示一个都用不了。 */
    public static final class Routed {

        private final SearchEngine engine;
        private final boolean degraded;

        Routed(SearchEngine engine, boolean degraded) {
            this.engine = engine;
            this.degraded = degraded;
        }

        static Routed none() {
            return new Routed(null, true);
        }

        public SearchEngine getEngine() {
            return engine;
        }

        /** true = 当前用的**不是**配置里的首选引擎（或根本没有引擎） */
        public boolean isDegraded() {
            return degraded;
        }

        public boolean isNone() {
            return engine == null;
        }
    }
}
