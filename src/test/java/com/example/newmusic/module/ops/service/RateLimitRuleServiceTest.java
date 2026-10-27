package com.example.newmusic.module.ops.service;

import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.redis.RedisGuard;
import com.example.newmusic.infra.ratelimit.RateLimitService;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.entity.RateLimitRule;
import com.example.newmusic.module.ops.mapper.RateLimitRuleMapper;
import com.example.newmusic.module.ops.ratelimit.LocalRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运维限流规则的单测（FR-28-001 / AC-28-001-1、AC-28-001-2）。
 *
 * <p>重点守一个容易被忽略的坑：**Redis 计数器键必须按资源分片**。
 * {@code /api/v1/player} 同时命中 PLAY 与 PLAY_REPORT 两条规则，
 * 如果键里没有资源段，两条规则会共用同一个滑动窗口，一次请求被计两次，
 * 每条规则的阈值相当于被悄悄减半——线上表现为「阈值 2400 却每秒就开始 429」。
 */
@DisplayName("RateLimitRuleService 规则匹配与计数键")
class RateLimitRuleServiceTest {

    private final List<String> counterKeys = new ArrayList<>();
    private RateLimitRuleMapper ruleMapper;
    private OpsConfigService configService;
    private RateLimitRuleService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(RateLimitRuleMapper.class);
        configService = mock(OpsConfigService.class);
        when(configService.getBool(anyString(), anyBoolean())).thenReturn(true);

        RedissonClient redisson = mock(RedissonClient.class);
        // 真实 RateLimitService 只在 eval 成功时返回 allow；这里用默认应答截获键名
        Answer<Object> captureKey = invocation -> {
            if ("eval".equals(invocation.getMethod().getName())) {
                List<?> keys = invocation.getArgument(3);
                counterKeys.add(String.valueOf(keys.get(0)));
                return 1L;
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        };
        RScript script = mock(RScript.class, captureKey);
        when(redisson.getScript(any())).thenReturn(script);

        RateLimitService rateLimitService = new RateLimitService(redisson, null,
                new RedisGuard(new InfraProperties()), new InfraProperties());

        service = new RateLimitRuleService(ruleMapper, rateLimitService,
                new LocalRateLimiter(), configService, new OpsProperties(), new ChaosState());
    }

    @Test
    @DisplayName("前缀重叠的两条规则使用各自的计数键")
    void overlappingRulesUseSeparateCounterKeys() {
        givenRules(rule("PLAY", "/api/v1/player", "GET", RateLimitRule.DIM_IP, 2400),
                rule("PLAY_REPORT", "/api/v1/play", null, RateLimitRule.DIM_IP, 2400));

        service.check(request("GET", "/api/v1/player/1", "1.2.3.4"));

        assertEquals(2, counterKeys.size(), counterKeys.toString());
        assertTrue(counterKeys.get(0).contains(":PLAY:/api/v1/player/1"), counterKeys.get(0));
        assertTrue(counterKeys.get(1).contains(":PLAY_REPORT:/api/v1/player/1"), counterKeys.get(1));
        assertTrue(counterKeys.stream().distinct().count() == 2, "两条规则不能共用一个键");
    }

    @Test
    @DisplayName("GLOBAL 维度按资源聚合，不按 path 分片")
    void globalDimensionAggregatesByResource() {
        givenRules(rule("GLOBAL", "/api/v1/", null, RateLimitRule.DIM_GLOBAL, 12000));

        service.check(request("GET", "/api/v1/search/hot", "1.2.3.4"));

        assertEquals(1, counterKeys.size());
        assertTrue(counterKeys.get(0).contains(":global:global:GLOBAL:all"), counterKeys.get(0));
    }

    private void givenRules(RateLimitRule... rules) {
        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(rules));
        service.refresh();
    }

    private static RateLimitRule rule(String resource, String pattern, String method,
                                      String dimension, int threshold) {
        RateLimitRule rule = new RateLimitRule();
        rule.setResource(resource);
        rule.setMatchPattern(pattern);
        rule.setHttpMethod(method);
        rule.setDimension(dimension);
        rule.setThreshold(threshold);
        rule.setWindowSeconds(60);
        rule.setEnabled(1);
        return rule;
    }

    private static HttpServletRequest request(String method, String uri, String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getHeader("X-Forwarded-For")).thenReturn(ip);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }
}
