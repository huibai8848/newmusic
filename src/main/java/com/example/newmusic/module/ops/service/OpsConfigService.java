package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.admin.entity.SysConfig;
import com.example.newmusic.module.admin.entity.SysConfigHistory;
import com.example.newmusic.module.admin.mapper.SysConfigHistoryMapper;
import com.example.newmusic.module.admin.mapper.SysConfigMapper;
import com.example.newmusic.module.ops.config.OpsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维配置缓存（FR-28-014）。
 *
 * <p>配置的真源是 {@code t_sys_config}，这里只做「定时全量拉取 + 本地缓存」：
 * 读配置是每个请求都要做的事（降级等级、限流开关、采样率），不能每次都查库；
 * 但也不能只靠内存——多实例部署时，A 实例改了配置，B 实例必须在一个刷新周期内看到。
 * 因此全量拉取、按时间窗口刷新，周期默认 5 秒（{@link OpsProperties#getConfigRefreshSeconds()}）。
 *
 * <p><b>读不到时返回调用方给的默认值</b>（BR-28-162），绝不抛异常：
 * 配置中心故障不应把业务拖死（EX-28-003-1 / EX-28-014-1）。
 */
@Slf4j
@Service
public class OpsConfigService {

    /** 降级等级（0~5），公开配置，前端横幅读它。 */
    public static final String KEY_DEGRADE_LEVEL = "ops.degrade.level";
    public static final String KEY_RATE_LIMIT_ENABLED = "ops.rate.limit.enabled";
    public static final String KEY_TRACE_SAMPLE_RATE = "ops.trace.sample-rate";
    public static final String KEY_ALERT_EVAL_ENABLED = "ops.alert.eval.enabled";
    public static final String KEY_METRICS_SNAPSHOT_ENABLED = "ops.metrics.snapshot.enabled";
    public static final String KEY_COST_DAILY_BUDGET_CENT = "ops.cost.daily-budget-cent";
    public static final String KEY_BACKUP_RETENTION_DAYS = "ops.backup.retention-days";
    public static final String KEY_CHAOS_ENABLED = "ops.chaos.enabled";

    private static final long NEVER = Long.MIN_VALUE;

    private final SysConfigMapper configMapper;
    private final SysConfigHistoryMapper historyMapper;
    private final OpsProperties properties;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt = NEVER;

    public OpsConfigService(SysConfigMapper configMapper, SysConfigHistoryMapper historyMapper,
                            OpsProperties properties) {
        this.configMapper = configMapper;
        this.historyMapper = historyMapper;
        this.properties = properties;
    }

    /** 全部 ops.* 配置（后台展示用）。 */
    public Map<String, String> all() {
        refreshIfStale();
        Map<String, String> sorted = new LinkedHashMap<>();
        cache.keySet().stream().filter(k -> k.startsWith("ops.")).sorted()
                .forEach(k -> sorted.put(k, cache.get(k)));
        return sorted;
    }

    public String get(String key, String defaultValue) {
        refreshIfStale();
        String value = cache.get(key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = get(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = get(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = get(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 降级等级：非法值按 0（正常）处理，避免一行脏配置让全站降级。 */
    public int degradeLevel() {
        int level = getInt(KEY_DEGRADE_LEVEL, 0);
        return level < 0 || level > 5 ? 0 : level;
    }

    /**
     * 写配置值并立即更新本地缓存。历史记录由本方法写，权限校验在 Controller。
     *
     * <p>不调用 {@code SysConfigService}：那条路径依赖 {@code UserContext}（请求线程），
     * 而运维配置可能在定时任务或启动自检里被修正。operator 显式传入，语义更清楚。
     */
    @Transactional
    public void set(String key, String value, Long operatorId, String remark) {
        SysConfig config = configMapper.selectOne(new QueryWrapper<SysConfig>()
                .eq("config_key", key).last("LIMIT 1"));
        if (config == null) {
            throw new IllegalArgumentException("配置键不存在：" + key);
        }
        String oldValue = config.getConfigValue();
        config.setConfigValue(value);
        config.setUpdateBy(operatorId);
        config.setUpdateTime(LocalDateTime.now());
        configMapper.updateById(config);

        SysConfigHistory history = new SysConfigHistory();
        history.setConfigKey(key);
        history.setOldValue(oldValue);
        history.setNewValue(value);
        history.setValueType(config.getValueType());
        history.setChangeType("UPDATE");
        history.setChangedBy(operatorId);
        history.setChangedByName(operatorId == null ? "system" : String.valueOf(operatorId));
        history.setRemark(remark == null ? "运维后台修改" : remark);
        history.setCreateTime(LocalDateTime.now());
        historyMapper.insert(history);

        cache.put(key, value);
        log.info("运维配置已更新 key={} old={} new={} operator={}", key, oldValue, value, operatorId);
    }

    /** 强制下次读取重新拉库（降级等级变更后调用，避免 5 秒窗口内的不一致）。 */
    public void invalidate() {
        loadedAt = NEVER;
    }

    public void refresh() {
        try {
            List<SysConfig> rows = configMapper.selectList(new QueryWrapper<SysConfig>()
                    .eq("status", 1));
            Map<String, String> latest = new ConcurrentHashMap<>();
            if (rows != null) {
                for (SysConfig row : rows) {
                    if (row.getConfigKey() != null) {
                        latest.put(row.getConfigKey(), row.getConfigValue());
                    }
                }
            }
            cache.clear();
            cache.putAll(latest);
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // 保留上一份缓存（EX-28-003-1：配置中心不可用时使用上次有效值）
            loadedAt = System.currentTimeMillis();
            log.warn("拉取运维配置失败，继续使用本地缓存 keys={} cause={}", cache.size(), e.toString());
        }
    }

    private void refreshIfStale() {
        long interval = Math.max(1, properties.getConfigRefreshSeconds()) * 1000L;
        if (isStale(interval)) {
            synchronized (this) {
                if (isStale(interval)) {
                    refresh();
                }
            }
        }
    }

    /** 先判 NEVER 再算差值：{@code now - Long.MIN_VALUE} 会溢出成负数，导致永不刷新。 */
    private boolean isStale(long interval) {
        return loadedAt == NEVER || System.currentTimeMillis() - loadedAt >= interval;
    }

    /** 只读快照，便于健康检查与自检输出。 */
    public Map<String, String> rawSnapshot() {
        refreshIfStale();
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
    }
}
