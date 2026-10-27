package com.example.newmusic.module.ops.breaker;

import com.example.newmusic.module.ops.config.OpsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 应用层熔断器（FR-28-002 / AC-28-002-1~8）。
 *
 * <p>状态机与 PRD 一致：CLOSED →（失败率超阈值且样本数够）→ OPEN →（等待窗结束）→
 * HALF_OPEN →（探测成功）CLOSED /（探测失败）OPEN 且等待窗翻倍。
 *
 * <p><b>为什么自己实现而不是引 Sentinel</b>：本机没有 Sentinel 控制台，而依赖白名单里也没有
 * Sentinel；更重要的是熔断的核心是「状态机 + 统计窗口」，三十行代码就能说清楚。
 * 失败率统计用固定长度环形队列（最近 N 次调用的布尔结果），比滑动时间窗更容易验证。
 *
 * <p>熔断对象是按名字注册的；未注册的名字首次使用时按默认参数创建。
 * 打开/关闭都打日志（供告警采集），状态通过 {@code /api/v1/admin/circuit-breakers} 暴露。
 */
@Slf4j
@Component
public class CircuitBreakerService {

    public static final String LLM = "llm";
    public static final String SEARCH_ES = "search_es";
    public static final String AUDIT_THIRD_PARTY = "audit_third_party";

    private final OpsProperties.Circuit config;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerService(OpsProperties properties) {
        this.config = properties.getCircuit();
        registerDefaults();
    }

    private void registerDefaults() {
        breaker("recommend");
        breaker("vector");
        breaker("track");
        breaker("storage");
        breaker("sms");
        breaker("email");
        breaker(LLM);
        breaker(SEARCH_ES);
        breaker(AUDIT_THIRD_PARTY);
    }

    public Breaker breaker(String name) {
        return breakers.computeIfAbsent(name, Breaker::new);
    }

    /**
     * 只做熔断判定与统计，**不吞原始异常**。
     *
     * <p>{@link #execute} 在失败时直接返回 fallback，适合「有兜底值」的调用；
     * 但 AI Provider 需要把 {@code AI_UNAVAILABLE} 的具体原因（未配置/超时/上游报错）
     * 原样传给用户，所以用这个版本：熔断打开抛 {@link OpenException}，
     * 业务异常照原样抛，调用方自己决定文案。
     */
    public <T> T call(String name, Supplier<T> call) {
        Breaker breaker = breaker(name);
        if (!breaker.tryAcquire()) {
            throw new OpenException(name);
        }
        try {
            T result = call.get();
            breaker.record(true);
            return result;
        } catch (RuntimeException e) {
            breaker.record(false);
            throw e;
        }
    }

    /** 熔断打开时抛 {@link OpenException}，由调用方转成降级路径。 */
    public <T> T execute(String name, Supplier<T> call, Supplier<T> fallback) {
        Breaker breaker = breaker(name);
        if (!breaker.tryAcquire()) {
            return fallback.get();
        }
        try {
            T result = call.get();
            breaker.record(true);
            return result;
        } catch (RuntimeException e) {
            breaker.record(false);
            if (fallback != null) {
                log.debug("熔断器 {} 记录失败并走降级：{}", name, e.toString());
                return fallback.get();
            }
            throw e;
        }
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Breaker breaker : breakers.values()) {
            list.add(breaker.snapshot());
        }
        list.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return list;
    }

    public int openCount() {
        int open = 0;
        for (Breaker breaker : breakers.values()) {
            if (!"CLOSED".equals(breaker.state())) {
                open++;
            }
        }
        return open;
    }

    public long openedTotal() {
        long total = 0;
        for (Breaker breaker : breakers.values()) {
            total += breaker.openTotal.get();
        }
        return total;
    }

    /** 单个熔断器。所有状态变更都在实例锁内完成，调用本身不持锁。 */
    public final class Breaker {

        private final String name;
        private final Deque<Boolean> window = new ArrayDeque<>();
        private final int windowSize;
        private final AtomicLong openTotal = new AtomicLong();

        private String state = "CLOSED";
        private long openUntil = 0L;
        private long currentOpenMillis;
        private int halfOpenCalls = 0;
        private int halfOpenSuccess = 0;
        private long lastTransitionAt = 0L;
        private String lastTransition = "初始化";

        private Breaker(String name) {
            this.name = name;
            this.windowSize = Math.max(5, config.getMinimumCalls() * 2);
            this.currentOpenMillis = config.getOpenSeconds() * 1000L;
        }

        /** @return 本次是否放行 */
        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if ("OPEN".equals(state)) {
                if (now < openUntil) {
                    return false;
                }
                transition("HALF_OPEN", "冷却结束，放行探测");
                halfOpenCalls = 0;
                halfOpenSuccess = 0;
            }
            if ("HALF_OPEN".equals(state)) {
                if (halfOpenCalls >= Math.max(1, config.getHalfOpenMaxCalls())) {
                    return false;
                }
                halfOpenCalls++;
            }
            return true;
        }

        synchronized void record(boolean success) {
            if ("HALF_OPEN".equals(state)) {
                if (success) {
                    halfOpenSuccess++;
                    if (halfOpenSuccess >= Math.max(1, config.getHalfOpenMaxCalls())) {
                        window.clear();
                        currentOpenMillis = config.getOpenSeconds() * 1000L;
                        transition("CLOSED", "半开探测全部成功，恢复正常");
                    }
                } else {
                    currentOpenMillis = Math.min(currentOpenMillis * 2, config.getOpenSeconds() * 1000L * 10);
                    openUntil = System.currentTimeMillis() + currentOpenMillis;
                    transition("OPEN", "半开探测失败，继续熔断并延长冷却");
                }
                return;
            }
            if (!"CLOSED".equals(state)) {
                return;
            }
            window.addLast(success);
            while (window.size() > windowSize) {
                window.removeFirst();
            }
            int calls = window.size();
            if (calls < Math.max(1, config.getMinimumCalls())) {
                return;
            }
            int failures = 0;
            for (Boolean item : window) {
                if (!item) {
                    failures++;
                }
            }
            double failureRate = (double) failures / calls;
            if (failureRate >= config.getFailureRateThreshold()) {
                openUntil = System.currentTimeMillis() + currentOpenMillis;
                openTotal.incrementAndGet();
                transition("OPEN", String.format("失败率 %.0f%% ≥ %.0f%%，打开 %dms",
                        failureRate * 100, config.getFailureRateThreshold() * 100, currentOpenMillis));
            }
        }

        private void transition(String next, String reason) {
            String previous = state;
            state = next;
            lastTransitionAt = System.currentTimeMillis();
            lastTransition = reason;
            if ("OPEN".equals(next)) {
                log.warn("熔断器打开 name={} previous={} openMs={} reason={}",
                        name, previous, currentOpenMillis, reason);
            } else {
                log.info("熔断器状态变更 name={} {} → {} reason={}", name, previous, next, reason);
            }
        }

        public String state() {
            return state;
        }

        public synchronized Map<String, Object> snapshot() {
            int failures = 0;
            for (Boolean item : window) {
                if (!item) {
                    failures++;
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("state", state);
            map.put("calls", window.size());
            map.put("failures", failures);
            map.put("failureRate", window.isEmpty() ? 0d : (double) failures / window.size());
            map.put("failureRateThreshold", config.getFailureRateThreshold());
            map.put("minimumCalls", config.getMinimumCalls());
            map.put("openUntil", openUntil);
            map.put("openMillis", currentOpenMillis);
            map.put("openTotal", openTotal.get());
            map.put("halfOpenCalls", halfOpenCalls);
            map.put("lastTransition", lastTransition);
            map.put("lastTransitionAt", lastTransitionAt == 0L ? null : lastTransitionAt);
            return map;
        }

        public String getName() {
            return name;
        }
    }

    /** 熔断打开时的显式异常，供需要区分「降级」与「业务失败」的调用方使用。 */
    public static class OpenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OpenException(String name) {
            super("circuit breaker open: " + name);
        }
    }
}
