package com.example.newmusic.module.ops.service;

import com.example.newmusic.infra.redis.RedisGuard;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.metric.OpsMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（FR-28-011 / AC-28-011-1~5）。
 *
 * <p>刻意把「活着」「能接流量」「依赖是否健康」拆成三份：
 * <ul>
 *   <li>liveness：只看进程自身，**绝不查 DB/Redis**，否则依赖抖动会把容器反复重启；</li>
 *   <li>readiness：DB 必须可用；Redis 不可用只降级为 {@code DEGRADED}，因为限流器有本地兜底；</li>
 *   <li>health：给运维看的聚合视图，带上降级等级、熔断、混沌等运行态。</li>
 * </ul>
 */
@Slf4j
@Service
public class OpsHealthService {

    private final DataSource dataSource;
    private final RedisGuard redisGuard;
    private final DegradeService degradeService;
    private final RateLimitRuleService rateLimitRuleService;
    private final ChaosState chaosState;
    private final OpsMetricsService metricsService;

    public OpsHealthService(DataSource dataSource, RedisGuard redisGuard,
                            DegradeService degradeService, RateLimitRuleService rateLimitRuleService,
                            ChaosState chaosState, OpsMetricsService metricsService) {
        this.dataSource = dataSource;
        this.redisGuard = redisGuard;
        this.degradeService = degradeService;
        this.rateLimitRuleService = rateLimitRuleService;
        this.chaosState = chaosState;
        this.metricsService = metricsService;
    }

    public Map<String, Object> liveness() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "UP");
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    public Map<String, Object> readiness() {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean db = dbAvailable();
        boolean redis = redisGuard.isAvailable();
        map.put("status", db ? "UP" : "DOWN");
        map.put("db", db ? "UP" : "DOWN");
        // Redis 断开不摘流量：限流会退到本地计数器，业务仍可服务（EX-28-007-2）
        map.put("redis", redis ? "UP" : "DEGRADED");
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean db = dbAvailable();
        boolean redis = redisGuard.isAvailable();
        map.put("status", db ? "UP" : "DOWN");
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("db", db ? "UP" : "DOWN");
        checks.put("redis", redis ? "UP" : "DEGRADED");
        checks.put("ops", "UP");
        map.put("checks", checks);
        map.put("degradeLevel", degradeService.currentLevel());
        map.put("degradeLevelName", degradeService.currentLevelName());
        map.put("disabledFeatures", disabledFeatures());
        map.put("rateLimitRules", rateLimitRuleService.ruleCount());
        map.put("chaosRunning", chaosState.snapshot() != null);
        Map<String, Object> metrics = metricsService.snapshot();
        map.put("circuitOpen", metrics.get("circuitOpen"));
        map.put("uptimeSeconds", metrics.get("uptimeSeconds"));
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    public boolean dbAvailable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            log.warn("健康检查 DB 不可用 cause={}", e.toString());
            return false;
        }
    }

    private int disabledFeatures() {
        int count = 0;
        for (com.example.newmusic.module.ops.entity.DegradeSwitch item : degradeService.switches()) {
            if (item.getEnabled() != null && item.getEnabled() == 0) {
                count++;
            }
        }
        return count;
    }
}
