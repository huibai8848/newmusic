package com.example.newmusic.infra.ratelimit;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.redis.RedisGuard;
import com.example.newmusic.infra.redis.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletResponse;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 滑动窗口限流（FR-12-005）。
 *
 * <p>窗口用 ZSet 实现：score = 请求时刻（毫秒），member = 请求唯一标识。
 * 判定前先 {@code ZREMRANGEBYSCORE} 把滑出窗口的记录删掉，于是天然是「滑动」而非「固定」窗口——
 * 不会出现固定窗口那种「窗口边界前后各打满一轮、瞬时通过 2N 次」的问题。
 *
 * <p><b>与 PRD 脚本的一处偏离（必须记录）。</b>PRD 的 member 是
 * {@code now .. '-' .. math.random(100000)}。这在同一毫秒内的并发请求上会塌成一个成员：
 * ZADD 同成员是「更新分数」而不是「新增」，于是同一毫秒打进来的 N 个请求只计 1 次，
 * 限流直接失效。而 Lua 里的 {@code math.random} 也不是可靠的唯一性来源
 * （Redis 为保证复制确定性对脚本随机数有约束）。
 * 改为由 Java 侧传入唯一标识（{@code ARGV[4]}）拼进 member：
 * 脚本本身仍是纯确定性的，唯一性交给了本来就有的东西。
 */
@Slf4j
@Component
public class RateLimitService {

    /**
     * KEYS[1] 限流键；ARGV[1] 窗口毫秒；ARGV[2] 阈值；ARGV[3] 当前毫秒；ARGV[4] 请求唯一标识。
     * 返回 1 放行 / 0 拒绝。
     */
    private static final String LUA_SLIDING_WINDOW =
            "local key = KEYS[1]\n"
            + "local window = tonumber(ARGV[1])\n"
            + "local limit = tonumber(ARGV[2])\n"
            + "local now = tonumber(ARGV[3])\n"
            + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
            + "local count = redis.call('ZCARD', key)\n"
            + "if count < limit then\n"
            + "    redis.call('ZADD', key, now, now .. '-' .. ARGV[4])\n"
            + "    redis.call('PEXPIRE', key, window)\n"
            + "    return 1\n"
            + "else\n"
            + "    return 0\n"
            + "end";

    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final Set<String> whitelist;

    public RateLimitService(RedissonClient redisson, StringRedisTemplate redis,
                            RedisGuard guard, InfraProperties properties) {
        this.redisson = redisson;
        this.redis = redis;
        this.guard = guard;
        this.whitelist = new HashSet<>(properties.getLimit().getWhitelist());
    }

    /** Redis 是否可用；后台限流规则页用它区分「Redis 拒绝」与「本地兜底」。 */
    public boolean isAvailable() {
        return guard.isAvailable();
    }

    /** 配置的 IP 白名单（BR-12-027）。默认空——把 127.0.0.1 加白会让本机冒烟验证不到限流 */
    public boolean isWhitelisted(String identifier) {
        return identifier != null && whitelist.contains(identifier);
    }

    /**
     * 计数并判定。
     *
     * <p><b>Redis 不可用时一律放行</b>（BR-12-028）。限流是保护性措施，不是业务规则：
     * 它挂掉时的正确行为是「少一层保护」，而不是「全站拒绝服务」。
     */
    public Decision check(String type, RateLimitDimension dimension, String identifier,
                          int limit, int windowSeconds, String api) {
        if (identifier == null || limit <= 0) {
            return Decision.allow();
        }
        String key = RedisKeys.limit(type, dimension.name().toLowerCase(), identifier, api);
        long now = System.currentTimeMillis();
        long windowMillis = TimeUnit.SECONDS.toMillis(windowSeconds);

        Long allowed = guard.call("RATE_LIMIT " + key, () -> redisson.getScript(StringCodec.INSTANCE)
                .eval(RScript.Mode.READ_WRITE, LUA_SLIDING_WINDOW, RScript.ReturnType.INTEGER,
                        Collections.<Object>singletonList(key),
                        String.valueOf(windowMillis), String.valueOf(limit), String.valueOf(now),
                        IdUtil.fastSimpleUUID()), null);

        if (allowed == null) {
            // null 有两种来源：Redis 故障，或脚本异常。两者都按「放行 + 告警」处理。
            // 返回 fallback 标记而不是普通 allow：模块 19 的全局限流器据此切到
            // 单机滑动窗口（AC-28-106），而不是把「Redis 挂了」直接等同于「不限流」。
            log.warn("限流判定不可用，本次放行并标记降级 key={}", key);
            return Decision.allowFallback();
        }
        if (allowed == 1L) {
            return Decision.allow();
        }
        return Decision.reject(retryAfterSeconds(key, now, windowMillis));
    }

    /**
     * 拒绝时算 {@code Retry-After}：窗口内最早那次请求滑出窗口的时刻，就是最早可能被放行的时刻。
     * 只在拒绝路径上多一次往返，正常请求不受影响。
     */
    private long retryAfterSeconds(String key, long now, long windowMillis) {
        Long earliest = guard.call("ZRANGE " + key, () -> {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(key, 0, 0);
            if (tuples == null || tuples.isEmpty()) {
                return null;
            }
            Double score = tuples.iterator().next().getScore();
            return score == null ? null : score.longValue();
        }, null);

        if (earliest == null) {
            // 拿不到就退化为「最多等一个窗口」，宁可让客户端多等，也不要给一个偏小的值让它白撞
            return windowMillis / 1000;
        }
        long waitMillis = earliest + windowMillis - now;
        return Math.max(1, (waitMillis + 999) / 1000);
    }

    /**
     * 判定并**拒绝**：超限时写 {@code Retry-After} 响应头并抛 10004。
     *
     * <p>把「写响应头 + 抛异常」这两步收在这里，是为了让注解织入（{@link RateLimitAspect}）
     * 与业务代码里的显式调用（登录流程的账号维度）走同一条路径——两处各写一遍的话，
     * 迟早会有一处忘记设 Retry-After，而那种不一致只在压测时才看得出来。
     *
     * @param message 自定义提示语，为空则用「请 N 秒后再试」
     */
    public void enforce(String type, RateLimitDimension dimension, String identifier,
                        int limit, int windowSeconds, String api, String message) {
        Decision decision = check(type, dimension, identifier, limit, windowSeconds, api);
        if (decision.isAllowed()) {
            return;
        }
        writeRetryAfter(decision.getRetryAfterSeconds());
        throw new BizException(ResultCode.RATE_LIMITED, message != null && !message.isEmpty()
                ? message
                : "请求过于频繁，请 " + decision.getRetryAfterSeconds() + " 秒后再试");
    }

    /**
     * 写 {@code Retry-After}。放在响应头而不是只放在消息里：它是 HTTP 标准语义，
     * 浏览器与网关都认得，客户端不必去解析提示文案里的数字。
     */
    private void writeRetryAfter(long retryAfterSeconds) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        HttpServletResponse response = attributes.getResponse();
        if (response != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
    }

    /** 限流判定结果。 */
    public static final class Decision {

        private static final Decision ALLOW = new Decision(true, 0, false);
        private static final Decision ALLOW_FALLBACK = new Decision(true, 0, true);

        private final boolean allowed;
        private final long retryAfterSeconds;
        private final boolean fallback;

        private Decision(boolean allowed, long retryAfterSeconds, boolean fallback) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.fallback = fallback;
        }

        static Decision allow() {
            return ALLOW;
        }

        static Decision reject(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds, false);
        }

        /** Redis 不可用：本次放行，但调用方应切到本地限流。 */
        static Decision allowFallback() {
            return ALLOW_FALLBACK;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public boolean isFallback() {
            return fallback;
        }
    }
}
