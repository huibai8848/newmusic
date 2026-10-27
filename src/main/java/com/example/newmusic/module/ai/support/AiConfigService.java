package com.example.newmusic.module.ai.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.admin.entity.SysConfig;
import com.example.newmusic.module.admin.mapper.SysConfigMapper;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 运行时配置（{@code t_sys_config} 的 {@code ai.*} 键）。
 *
 * <p>为什么不直接读 {@link AiProperties}：PRD 要求预算、场景预算、模型路由可**热更新**
 * （后台 {@code PUT /admin/ai/budget}、{@code /model-routing}）。重启才能生效的配置
 * 在「预算爆了」这种场景下等于没有。
 *
 * <p>刷新窗口 30 秒。AI 调用本身就是慢路径（秒级），30 秒的配置滞后可以接受；
 * 反过来每次调用查一次库则会把成本省下来的钱花在数据库上。
 * 后台改配置后会主动 {@link #invalidate()}，所以「点了保存却要等 30 秒」不会发生。
 */
@Slf4j
@Component
public class AiConfigService {

    public static final String KEY_DAILY_BUDGET = "ai.daily.budget.cent";
    public static final String KEY_ALERT_PERCENT = "ai.budget.alert.percent";
    public static final String KEY_SCENE_BUDGET = "ai.scene.budget.cent";
    public static final String KEY_MODEL_ROUTING = "ai.model.routing";
    public static final String KEY_CACHE_ENABLED = "ai.cache.enabled";
    public static final String KEY_SENSITIVE_WORDS = "ai.sensitive.words";

    private static final long REFRESH_MILLIS = 30_000L;

    private final SysConfigMapper configMapper;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot = Snapshot.defaults();
    private volatile long loadedAt = 0L;

    public AiConfigService(SysConfigMapper configMapper, AiProperties properties,
                           ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public long dailyBudgetCent() {
        return current().dailyBudgetCent;
    }

    public int alertPercent() {
        return current().alertPercent;
    }

    public long sceneBudgetCent(AiScene scene) {
        Long value = current().sceneBudget.get(scene.code());
        return value == null ? 0L : value;
    }

    /** 场景 → 档位；没配置时用枚举默认值。 */
    public AiScene.Level levelOf(AiScene scene) {
        return AiScene.Level.of(current().routing.get(scene.code()), scene.getDefaultLevel());
    }

    public boolean cacheEnabled() {
        return current().cacheEnabled;
    }

    public Set<String> sensitiveWords() {
        return current().sensitiveWords;
    }

    /** 后台改完配置后调用，下一次读取立即回源。 */
    public void invalidate() {
        loadedAt = 0L;
    }

    /** 当前生效值（给后台「生效配置」展示与排查用）。 */
    public Map<String, Object> currentView() {
        Snapshot s = current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dailyBudgetCent", s.dailyBudgetCent);
        view.put("alertPercent", s.alertPercent);
        view.put("sceneBudgetCent", s.sceneBudget);
        view.put("modelRouting", s.routing);
        view.put("cacheEnabled", s.cacheEnabled);
        view.put("sensitiveWordCount", s.sensitiveWords.size());
        return view;
    }

    private Snapshot current() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < REFRESH_MILLIS) {
            return snapshot;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < REFRESH_MILLIS) {
                return snapshot;
            }
            snapshot = load();
            loadedAt = System.currentTimeMillis();
            return snapshot;
        }
    }

    private Snapshot load() {
        Snapshot s = Snapshot.defaults();
        s.dailyBudgetCent = properties.getDailyBudgetCent();
        s.alertPercent = properties.getAlertPercent();
        s.cacheEnabled = true;
        try {
            List<SysConfig> rows = configMapper.selectList(new QueryWrapper<SysConfig>()
                    .in("config_key", Arrays.asList(KEY_DAILY_BUDGET, KEY_ALERT_PERCENT,
                            KEY_SCENE_BUDGET, KEY_MODEL_ROUTING, KEY_CACHE_ENABLED,
                            KEY_SENSITIVE_WORDS))
                    .eq("status", 1));
            for (SysConfig row : rows) {
                apply(s, row.getConfigKey(), row.getConfigValue());
            }
        } catch (RuntimeException e) {
            // 配置读不到时用 YAML/默认值继续跑：AI 是增强能力，不能因为配置表故障而全站不可用
            log.warn("读取 AI 配置失败，使用默认值 cause={}", e.toString());
        }
        return s;
    }

    private void apply(Snapshot s, String key, String value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        try {
            switch (key) {
                case KEY_DAILY_BUDGET:
                    s.dailyBudgetCent = Long.parseLong(value.trim());
                    break;
                case KEY_ALERT_PERCENT:
                    s.alertPercent = Integer.parseInt(value.trim());
                    break;
                case KEY_CACHE_ENABLED:
                    s.cacheEnabled = Boolean.parseBoolean(value.trim());
                    break;
                case KEY_SENSITIVE_WORDS:
                    s.sensitiveWords = parseWords(value);
                    break;
                case KEY_SCENE_BUDGET:
                    s.sceneBudget = parseLongMap(value);
                    break;
                case KEY_MODEL_ROUTING:
                    s.routing = parseStringMap(value);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("AI 配置项格式不合法，忽略 key={} value={} cause={}", key, value, e.toString());
        }
    }

    private Set<String> parseWords(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String part : value.split("[,，]")) {
            String word = part.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return Collections.unmodifiableSet(words);
    }

    private Map<String, Long> parseLongMap(String value) throws Exception {
        Map<String, Long> map = objectMapper.readValue(value, new TypeReference<Map<String, Long>>() { });
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private Map<String, String> parseStringMap(String value) throws Exception {
        Map<String, String> map = objectMapper.readValue(value, new TypeReference<Map<String, String>>() { });
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static final class Snapshot {
        private long dailyBudgetCent = 30000;
        private int alertPercent = 80;
        private Map<String, Long> sceneBudget = Collections.emptyMap();
        private Map<String, String> routing = Collections.emptyMap();
        private boolean cacheEnabled = true;
        private Set<String> sensitiveWords = Collections.emptySet();

        private static Snapshot defaults() {
            Snapshot s = new Snapshot();
            s.sceneBudget = Collections.emptyMap();
            s.routing = Collections.emptyMap();
            s.sensitiveWords = Collections.emptySet();
            return s;
        }
    }
}
