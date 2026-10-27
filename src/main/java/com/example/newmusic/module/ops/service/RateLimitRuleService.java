package com.example.newmusic.module.ops.service;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.infra.ratelimit.RateLimitService;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.dto.RateLimitRuleUpdateReq;
import com.example.newmusic.module.ops.dto.RateLimitRuleVO;
import com.example.newmusic.module.ops.entity.RateLimitRule;
import com.example.newmusic.module.ops.mapper.RateLimitRuleMapper;
import com.example.newmusic.module.ops.ratelimit.LocalRateLimiter;
import com.example.newmusic.module.ops.chaos.ChaosState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 应用层限流（FR-28-001 / AC-28-001-1~8）。
 *
 * <p>规则来自 {@code t_ops_rate_limit_rule}，最长前缀匹配；规则每 3 秒热刷新，
 * 后台改完阈值无需重启（AC-28-001-4）。
 *
 * <p><b>Redis 故障降级（AC-28-001-8）</b>：{@link RateLimitService} 在 Redis 失败时返回
 * fallback 标记，这里切到 {@link LocalRateLimiter} 的单机滑动窗口，而不是直接放行。
 * 混沌实验 {@code REDIS_LIMITER_UNAVAILABLE} 也会走同一条路径——故障演练不是靠 mock，
 * 而是让真实代码进入真实的降级分支。
 *
 * <p>白名单：规则自己的 {@code whitelist_ips} + 全局配置 {@code newmusic.infra.limit.whitelist}。
 * 超管豁免（与模块 03 的 @RateLimit 一致），否则运维排障会把自己锁在门外。
 */
@Slf4j
@Service
public class RateLimitRuleService {

    /** 请求被限流时写入的响应头。 */
    public static final String RETRY_AFTER = "Retry-After";

    private static final Comparator<RateLimitRule> LONGEST_FIRST =
            Comparator.comparingInt((RateLimitRule r) -> r.getMatchPattern() == null ? 0 : r.getMatchPattern().length())
                    .reversed();

    private final RateLimitRuleMapper ruleMapper;
    private final RateLimitService rateLimitService;
    private final LocalRateLimiter localRateLimiter;
    private final OpsConfigService configService;
    private final OpsProperties properties;
    private final ChaosState chaosState;

    private volatile List<RateLimitRule> rules = Collections.emptyList();
    // 用 0 而不是 Long.MIN_VALUE：now - Long.MIN_VALUE 会溢出成负数，条件永远不成立，
    // 规则就永远不会加载（限流形同虚设）。0 保证首次访问必定刷新。
    private volatile long loadedAt = 0L;
    private volatile boolean lastLoadFailed = false;

    private final Map<String, AtomicLong> allowedCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> blockedCounters = new ConcurrentHashMap<>();

    public RateLimitRuleService(RateLimitRuleMapper ruleMapper, RateLimitService rateLimitService,
                                LocalRateLimiter localRateLimiter, OpsConfigService configService,
                                OpsProperties properties, ChaosState chaosState) {
        this.ruleMapper = ruleMapper;
        this.rateLimitService = rateLimitService;
        this.localRateLimiter = localRateLimiter;
        this.configService = configService;
        this.properties = properties;
        this.chaosState = chaosState;
    }

    /** 每 3 秒检查一次是否需要重新加载规则（多实例下别的实例改了阈值也能生效）。 */
    @Scheduled(fixedDelayString = "${newmusic.ops.rule-refresh-interval-ms:1000}")
    public void scheduledRefresh() {
        long interval = Math.max(1, properties.getRateRuleRefreshSeconds()) * 1000L;
        if (System.currentTimeMillis() - loadedAt >= interval) {
            refresh();
        }
        localRateLimiter.cleanup();
    }

    public synchronized void refresh() {
        try {
            List<RateLimitRule> loaded = ruleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RateLimitRule>()
                            .eq("enabled", 1));
            if (loaded == null) {
                loaded = new ArrayList<>();
            }
            loaded.sort(LONGEST_FIRST);
            rules = loaded;
            loadedAt = System.currentTimeMillis();
            if (lastLoadFailed) {
                log.info("限流规则已恢复加载 rules={}", loaded.size());
            }
            lastLoadFailed = false;
        } catch (Exception e) {
            // EX-28-001-3：加载失败沿用上一份规则，而不是清空规则导致全站裸奔
            lastLoadFailed = true;
            loadedAt = System.currentTimeMillis();
            log.warn("限流规则加载失败，沿用上一份 rules={} cause={}", rules.size(), e.toString());
        }
    }

    public List<RateLimitRuleVO> list() {
        ensureFresh();
        List<RateLimitRuleVO> result = new ArrayList<>(rules.size());
        for (RateLimitRule rule : rules) {
            RateLimitRuleVO vo = new RateLimitRuleVO();
            BeanUtils.copyProperties(rule, vo);
            vo.setAllowedTotal(counter(allowedCounters, rule.getResource()).get());
            vo.setBlockedTotal(counter(blockedCounters, rule.getResource()).get());
            vo.setRedisAvailable(rateLimitService.isAvailable());
            vo.setLocalFallbackEnabled(properties.isLocalFallbackEnabled());
            result.add(vo);
        }
        return result;
    }

    @Transactional
    public RateLimitRuleVO update(String resource, RateLimitRuleUpdateReq req, Long operatorId) {
        RateLimitRule rule = ruleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RateLimitRule>()
                        .eq("resource", resource).last("LIMIT 1"));
        if (rule == null || isMissing(rule)) {
            throw new BizException(ResultCode.OPS_RULE_NOT_FOUND);
        }
        if (req.getThreshold() != null) {
            rule.setThreshold(req.getThreshold());
        }
        if (req.getWindowSeconds() != null) {
            rule.setWindowSeconds(req.getWindowSeconds());
        }
        if (req.getDimension() != null) {
            rule.setDimension(req.getDimension());
        }
        if (req.getHttpMethod() != null) {
            rule.setHttpMethod(req.getHttpMethod().isEmpty() ? null : req.getHttpMethod());
        }
        if (req.getEnabled() != null) {
            rule.setEnabled(req.getEnabled() == 0 ? 0 : 1);
        }
        if (req.getWhitelistIps() != null) {
            rule.setWhitelistIps(req.getWhitelistIps());
        }
        if (req.getDescription() != null) {
            rule.setDescription(req.getDescription());
        }
        rule.setOperatorId(operatorId);
        rule.setVersion(rule.getVersion() == null ? 1 : rule.getVersion() + 1);
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.updateById(rule);
        refresh();
        log.info("限流规则已更新 resource={} threshold={}/{}s enabled={} operator={}",
                resource, rule.getThreshold(), rule.getWindowSeconds(), rule.getEnabled(), operatorId);
        RateLimitRuleVO vo = new RateLimitRuleVO();
        BeanUtils.copyProperties(rule, vo);
        return vo;
    }

    /** 全站限流总开关（配置中心热更新，关闭后只观测不拦截）。 */
    public boolean enabled() {
        return configService.getBool(OpsConfigService.KEY_RATE_LIMIT_ENABLED, true);
    }

    /**
     * 在请求进入业务前判定。超限时写 {@code Retry-After} 并抛 10004。
     *
     * @param request 当前请求
     */
    public void check(HttpServletRequest request) {
        if (!properties.isEnabled() || !enabled()) {
            return;
        }
        LoginUser loginUser = UserContext.get();
        if (loginUser != null && loginUser.isSuperAdmin()) {
            return;
        }
        ensureFresh();
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path)) {
            return;
        }
        String ip = IpUtil.getClientIp(request);
        for (RateLimitRule rule : rules) {
            if (!matches(rule, request.getMethod(), path)) {
                continue;
            }
            if (isWhitelisted(rule, ip)) {
                continue;
            }
            String identifier = identifier(rule, request, loginUser, ip);
            if (identifier == null) {
                continue;
            }
            Decision decision = evaluate(rule, path, identifier);
            if (!decision.allowed) {
                counter(blockedCounters, rule.getResource()).incrementAndGet();
                writeRetryAfter(decision.retryAfterSeconds);
                throw new BizException(ResultCode.RATE_LIMITED,
                        "请求过于频繁（" + rule.getResource() + "），请 "
                                + decision.retryAfterSeconds + " 秒后再试");
            }
            counter(allowedCounters, rule.getResource()).incrementAndGet();
        }
    }

    private Decision evaluate(RateLimitRule rule, String path, String identifier) {
        int limit = rule.getThreshold() == null ? 0 : rule.getThreshold();
        int window = rule.getWindowSeconds() == null ? 60 : rule.getWindowSeconds();
        // GLOBAL 维度按资源聚合（不按 path 分片），否则「全站限流」会变成「每个接口各自限流」
        String api = RateLimitRule.DIM_GLOBAL.equalsIgnoreCase(rule.getDimension()) ? "all" : path;
        // Redis 键必须带上资源名：不同规则的 URI 前缀可能重叠（/api/v1/player 同时命中
        // PLAY 与 PLAY_REPORT），只按「维度 + 标识 + 路径」做键会让两条规则共用一个计数器，
        // 一次请求被记两次，等于把每条规则的阈值都悄悄减半。
        String counterApi = rule.getResource() + ':' + api;
        String key = "ops:" + rule.getResource() + ':' + rule.getDimension().toLowerCase() + ':'
                + identifier + ':' + api;

        long now = System.currentTimeMillis();
        boolean redisDown = chaosState.isActive(ChaosState.FAULT_REDIS_LIMITER);
        if (!redisDown) {
            RateLimitService.Decision decision = rateLimitService.check("ops", dimension(rule), identifier,
                    limit, window, counterApi);
            if (decision.isAllowed() && !decision.isFallback()) {
                return Decision.allow();
            }
            if (!decision.isAllowed()) {
                return Decision.reject(decision.getRetryAfterSeconds());
            }
        }
        // Redis 故障 / 混沌注入：降级为单机滑动窗口（AC-28-001-8）
        if (!properties.isLocalFallbackEnabled()) {
            return Decision.allow();
        }
        if (localRateLimiter.tryAcquire(key, limit, window, now)) {
            return Decision.allow();
        }
        return Decision.reject(localRateLimiter.retryAfterSeconds(key, window, now));
    }

    private RateLimitDimension dimension(RateLimitRule rule) {
        if (RateLimitRule.DIM_USER.equalsIgnoreCase(rule.getDimension())) {
            return RateLimitDimension.USER;
        }
        if (RateLimitRule.DIM_GLOBAL.equalsIgnoreCase(rule.getDimension())) {
            return RateLimitDimension.GLOBAL;
        }
        return RateLimitDimension.IP;
    }

    private String identifier(RateLimitRule rule, HttpServletRequest request, LoginUser loginUser, String ip) {
        if (RateLimitRule.DIM_GLOBAL.equalsIgnoreCase(rule.getDimension())) {
            return "global";
        }
        if (RateLimitRule.DIM_USER.equalsIgnoreCase(rule.getDimension())) {
            // 游客没有 userId：用 IP 兜底，否则匿名流量完全绕过用户维度规则
            return loginUser != null ? String.valueOf(loginUser.getUserId()) : ip;
        }
        return ip;
    }

    private boolean matches(RateLimitRule rule, String method, String path) {
        if (!StringUtils.hasText(rule.getMatchPattern()) || !path.startsWith(rule.getMatchPattern())) {
            return false;
        }
        return !StringUtils.hasText(rule.getHttpMethod())
                || rule.getHttpMethod().equalsIgnoreCase(method);
    }

    private boolean isWhitelisted(RateLimitRule rule, String ip) {
        if (ip == null) {
            return false;
        }
        if (rateLimitService.isWhitelisted(ip)) {
            return true;
        }
        if (StringUtils.hasText(rule.getWhitelistIps())) {
            for (String item : rule.getWhitelistIps().split(",")) {
                if (ip.equals(item.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeRetryAfter(long seconds) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        HttpServletResponse response = attributes.getResponse();
        if (response != null) {
            response.setHeader(RETRY_AFTER, String.valueOf(seconds));
        }
    }

    public boolean isLastLoadFailed() {
        return lastLoadFailed;
    }

    public int ruleCount() {
        return rules.size();
    }

    public Map<String, Long> blockedSnapshot() {
        return snapshot(blockedCounters);
    }

    public void resetCounters() {
        allowedCounters.clear();
        blockedCounters.clear();
    }

    private void ensureFresh() {
        long interval = Math.max(1, properties.getRateRuleRefreshSeconds()) * 1000L;
        if (System.currentTimeMillis() - loadedAt >= interval) {
            refresh();
        }
    }

    private AtomicLong counter(Map<String, AtomicLong> map, String resource) {
        String key = resource == null ? "UNKNOWN" : resource;
        return map.computeIfAbsent(key, k -> new AtomicLong());
    }

    private Map<String, Long> snapshot(Map<String, AtomicLong> map) {
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().get(), (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private boolean isMissing(RateLimitRule rule) {
        return rule.getId() == null;
    }

    /** 判定结果。 */
    public static final class Decision {
        private final boolean allowed;
        private final long retryAfterSeconds;

        private Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static Decision allow() {
            return new Decision(true, 0);
        }

        static Decision reject(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
