package com.example.newmusic.module.search.support;

import com.example.newmusic.module.contentsafety.support.SensitiveWordEngine;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.mapper.SearchConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 搜索敏感词过滤（BR-15-002，系分 D-38）。
 *
 * <p><b>这个类当前不拦任何词，这是刻意的，也必须让使用者知道。</b>
 * BR-15-002 写的是「搜索关键词必须经过敏感词过滤」，但敏感词库属模块 18（内容安全），
 * 库里没有 {@code t_sensitive_word} 表。沿用模块 03 D-22 处理 IP 白名单的先例：
 * **结构就位、词典可配、默认空**——代码路径、错误码（70004）、可观测性全部可交付，
 * 运营往 {@code t_sys_config} 写一行 {@code search.sensitive.words} 即刻生效。
 * 把「代码里有这个类」当成「内容安全已就位」是本模块最容易产生的误判，故写在类注释第一段。
 *
 * <p><b>词典来源两级</b>：{@code t_sys_config} 的行优先（存在且非空白才覆盖），
 * 否则用 {@code newmusic.search.sensitive.words} 的 YAML 值。DB 读失败按 YAML 走并 WARN——
 * 内容安全词典读不到就放行全部关键词，是「少一层保护」，不是「拒绝服务」。
 *
 * <p><b>刷新窗口 5 秒。</b>词典变更要能被下一个请求看见（否则 AC-15-003-3 无法在进程外实测），
 * 又不能让每个搜索请求都打一次库。5 秒是这两者之间的折中：最坏情况下超标 5 秒生效。
 * 真词库上线后（模块 18）应改为发布订阅推送失效，而不是把 TTL 调小。
 */
@Slf4j
@Component
public class SensitiveWordSupport {

    /** 运营配置的键，与 {@code sql/06-search-seed.sql} 的说明保持一致 */
    public static final String CONFIG_KEY = "search.sensitive.words";

    private static final long REFRESH_MILLIS = 5_000L;

    /** 空词典常量：命中判断零开销，且避免每次刷新都新建对象 */
    private static final Set<String> EMPTY = Collections.emptySet();

    private final SearchProperties properties;
    private final SearchConfigMapper configMapper;
    /** 模块 18 的 DFA 引擎；单测可传 null，走 YAML/配置表兜底。 */
    private final SensitiveWordEngine engine;

    private volatile Set<String> dictionary = EMPTY;
    private volatile long loadedAt = 0L;

    /** 单测/无 Spring 场景：只用 YAML + 配置表。 */
    public SensitiveWordSupport(SearchProperties properties, SearchConfigMapper configMapper) {
        this(properties, configMapper, null);
    }

    @Autowired
    public SensitiveWordSupport(SearchProperties properties, SearchConfigMapper configMapper,
                                SensitiveWordEngine engine) {
        this.properties = properties;
        this.configMapper = configMapper;
        this.engine = engine;
    }

    /**
     * 是否必须拦截。只把 L3 与 REJECT 规则视为「非法内容」；
     * L1/L2 交给调用方决定是否转人工（{@link #needsReview(String)}），
     * 否则一条 L1 疑似词会把正常评论全部挡掉。
     */
    public boolean isBlocked(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }
        SensitiveWordEngine.Result result = detect(keyword);
        // 引擎有词库时 detect 总会返回 Result（哪怕零命中）。只有真正命中
        // L3 / REJECT 才直接放行拦截结论；L1/L2 或零命中仍要继续查
        // search.sensitive.words 的运营词典。二者取并集，而不是互相覆盖——
        // 否则模块 18 词库一上线，模块 06 的配置表入口就静默失效了
        // （verify-06 AC-15-003-3 / BR-15-027）。
        if (result != null && (result.getMaxLevel() >= 3 || result.hasRejectRule())) {
            return true;
        }
        Set<String> dict = dictionary();
        if (dict.isEmpty()) {
            return false;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        for (String word : dict) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 高风险文案（L2/L3 或任意规则命中）：昵称/简介等无人工复核入口的字段直接拒绝。 */
    public boolean isRisky(String text) {
        SensitiveWordEngine.Result result = detect(text);
        return result != null && (result.getMaxLevel() >= 2 || result.hasRejectRule()
                || result.hasReviewRule());
    }

    /** L1/L2 或 REVIEW 规则：发布但进入人工审核。 */
    public boolean needsReview(String text) {
        SensitiveWordEngine.Result result = detect(text);
        if (result == null) {
            return false;
        }
        return result.getMaxLevel() == 1 || result.getMaxLevel() == 2
                || result.hasReviewRule();
    }

    /** DFA 结果；词库未就绪（空/兜底且无词）时返回 null，调用方退回旧逻辑。 */
    public SensitiveWordEngine.Result detect(String text) {
        return detect(text, "ALL");
    }

    public SensitiveWordEngine.Result detect(String text, String scene) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (engine == null || engine.wordCount() == 0) {
            return null;
        }
        return engine.detect(text, scene);
    }

    /** 当前生效的词典（只读视图）。给断言与排查用——「为什么这个词没被拦」要看得到实际词表。 */
    public Set<String> currentDictionary() {
        return dictionary();
    }

    /** 强制下次访问重新加载。仅供测试与将来的配置变更推送使用。 */
    public void invalidate() {
        loadedAt = 0L;
    }

    private Set<String> dictionary() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < REFRESH_MILLIS) {
            return dictionary;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - loadedAt < REFRESH_MILLIS) {
                return dictionary;
            }
            dictionary = load();
            loadedAt = now;
            return dictionary;
        }
    }

    private Set<String> load() {
        String raw = null;
        if (configMapper != null) {
            try {
                raw = configMapper.selectValue(CONFIG_KEY);
            } catch (Exception e) {
                log.warn("读取敏感词配置失败，退回 YAML 配置 key={} cause={}", CONFIG_KEY, e.toString());
            }
        }
        if (!StringUtils.hasText(raw)) {
            raw = properties.getSensitive().getWords();
        }
        if (!StringUtils.hasText(raw)) {
            return EMPTY;
        }
        Set<String> words = Arrays.stream(raw.split("[,，\\n]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(w -> w.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return words.isEmpty() ? EMPTY : Collections.unmodifiableSet(words);
    }
}
