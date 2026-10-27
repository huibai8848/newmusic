package com.example.newmusic.infra.redis;

import com.example.newmusic.infra.InfraProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Redis 降级守卫（系分 D-20）。
 *
 * <p><b>为什么不是简单的 try-catch。</b>AC-12-001-4 要求 Redis 停掉后接口响应仍 ≤200ms。
 * 若每个请求都走完整的「连接 → 超时 → 异常」链路，单次代价就是一次 TCP 连接超时，
 * 200ms 预算瞬间爆掉。所以这里做**快速熔断**：连续失败到阈值就打开开关，
 * 打开期间所有 Redis 操作**直接短路、连调用都不发起**，冷却后再放一个请求去试探。
 *
 * <p>代价是：故障发生后只有前 {@code failure-threshold} 个请求会真正去等 Redis。
 *
 * <p>降级语义是**放行**（返回调用方给的默认值），不是抛异常——可用性优先，
 * 具体到每个能力怎么降级见系分 §6.5。
 */
@Slf4j
@Component
public class RedisGuard {

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断打开到的时刻（毫秒时间戳）。0 表示关闭 */
    private volatile long openUntil = 0L;

    public RedisGuard(InfraProperties properties) {
        this.failureThreshold = properties.getGuard().getFailureThreshold();
        this.openMillis = properties.getGuard().getOpenMillis();
    }

    /** 当前是否允许访问 Redis。熔断打开期间返回 false。 */
    public boolean isAvailable() {
        return System.currentTimeMillis() >= openUntil;
    }

    /**
     * 执行一次 Redis 操作。
     *
     * @param action   操作名，仅用于日志定位
     * @param supplier 真正的 Redis 调用
     * @param fallback 降级返回值
     */
    public <T> T call(String action, Supplier<T> supplier, T fallback) {
        if (!isAvailable()) {
            log.debug("Redis 熔断中，直接降级 action={}", action);
            return fallback;
        }
        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(action, e);
            return fallback;
        }
    }

    /** 无返回值的版本。 */
    public void run(String action, Runnable runnable) {
        call(action, () -> {
            runnable.run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    private void onSuccess() {
        if (consecutiveFailures.get() != 0) {
            consecutiveFailures.set(0);
            log.info("Redis 已恢复，熔断关闭");
        }
        openUntil = 0L;
    }

    private void onFailure(String action, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil = System.currentTimeMillis() + openMillis;
            log.error("Redis 连续失败 {} 次，熔断 {}ms；期间所有缓存/限流/锁检查将降级。action={} cause={}",
                    failures, openMillis, action, e.toString());
        } else {
            log.warn("Redis 操作失败({}/{}），本次降级 action={} cause={}",
                    failures, failureThreshold, action, e.toString());
        }
    }

    /** 供测试与运维观察。 */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
