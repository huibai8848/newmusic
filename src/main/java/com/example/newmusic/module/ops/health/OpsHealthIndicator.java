package com.example.newmusic.module.ops.health;

import com.example.newmusic.module.ops.service.DegradeService;
import com.example.newmusic.module.ops.service.OpsConfigService;
import com.example.newmusic.module.ops.service.RateLimitRuleService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 就绪组里的 {@code ops} 指标（AC-28-011-3）。
 *
 * <p>只报告「运维面自己是否就绪」：降级等级、限流规则是否加载成功。
 * <b>Redis 不在这个指标里判 DOWN</b>——限流有本地兜底，Redis 抖动不应把整机摘流量；
 * DB 由 Spring Boot 自带的 {@code db} 指标负责。
 */
@Component("ops")
public class OpsHealthIndicator implements HealthIndicator {

    private final DegradeService degradeService;
    private final RateLimitRuleService rateLimitRuleService;
    private final OpsConfigService configService;

    public OpsHealthIndicator(DegradeService degradeService,
                              RateLimitRuleService rateLimitRuleService,
                              OpsConfigService configService) {
        this.degradeService = degradeService;
        this.rateLimitRuleService = rateLimitRuleService;
        this.configService = configService;
    }

    @Override
    public Health health() {
        int level = degradeService.currentLevel();
        boolean rulesLoaded = !rateLimitRuleService.isLastLoadFailed();
        Health.Builder builder = rulesLoaded ? Health.up() : Health.status("DEGRADED");
        return builder
                .withDetail("degradeLevel", level)
                .withDetail("degradeLevelName", DegradeService.levelName(level))
                .withDetail("rateLimitRules", rateLimitRuleService.ruleCount())
                .withDetail("rateLimitRulesLoaded", rulesLoaded)
                .withDetail("rateLimitEnabled",
                        configService.getBool(OpsConfigService.KEY_RATE_LIMIT_ENABLED, true))
                .build();
    }
}
