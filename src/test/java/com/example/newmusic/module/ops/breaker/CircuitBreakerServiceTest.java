package com.example.newmusic.module.ops.breaker;

import com.example.newmusic.module.ops.config.OpsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断状态机单测（FR-28-002 / AC-28-002-1~8）。
 *
 * <p>重点守三件事：
 * 1. 样本数不足时不熔断（避免冷启动一两次失败就打开）；
 * 2. 打开后**不再调用**下游，直接走 fallback；
 * 3. 半开探测成功关闭、失败则继续打开并把冷却时间翻倍。
 */
@DisplayName("CircuitBreakerService 熔断状态机")
class CircuitBreakerServiceTest {

    private CircuitBreakerService service(double threshold, int minCalls, int openSeconds, int halfOpenCalls) {
        OpsProperties properties = new OpsProperties();
        properties.getCircuit().setFailureRateThreshold(threshold);
        properties.getCircuit().setMinimumCalls(minCalls);
        properties.getCircuit().setOpenSeconds(openSeconds);
        properties.getCircuit().setHalfOpenMaxCalls(halfOpenCalls);
        return new CircuitBreakerService(properties);
    }

    /** 连续失败直到熔断打开 */
    private void open(CircuitBreakerService service, String name, int times) {
        for (int i = 0; i < times; i++) {
            assertEquals("fallback", service.execute(name, () -> {
                throw new IllegalStateException("downstream down");
            }, () -> "fallback"));
        }
    }

    @Test
    @DisplayName("样本数不足时保持 CLOSED，失败仍走 fallback")
    void keepsClosedWhenSamplesInsufficient() {
        CircuitBreakerService service = service(0.5d, 4, 1, 2);

        assertEquals("fallback", service.execute("llm", () -> {
            throw new IllegalStateException("boom");
        }, () -> "fallback"));

        assertEquals("CLOSED", service.breaker("llm").state());
        assertEquals(0, service.openCount());
        assertEquals(0L, service.openedTotal());
    }

    @Test
    @DisplayName("失败率达到阈值后打开，打开期间不再调用下游")
    void opensWhenFailureRateReached() {
        CircuitBreakerService service = service(0.5d, 4, 1, 1);
        open(service, "llm", 4);

        assertEquals("OPEN", service.breaker("llm").state());
        assertEquals(1, service.openCount());
        assertEquals(1L, service.openedTotal());

        AtomicInteger downstreamCalls = new AtomicInteger();
        String result = service.execute("llm", () -> {
            downstreamCalls.incrementAndGet();
            return "real";
        }, () -> "fallback");

        assertEquals("fallback", result, "熔断打开时必须返回兜底值");
        assertEquals(0, downstreamCalls.get(), "熔断打开后不允许再打下游");

        Map<String, Object> snapshot = service.breaker("llm").snapshot();
        assertEquals("OPEN", snapshot.get("state"));
        assertEquals(1000L, snapshot.get("openMillis"));
    }

    @Test
    @DisplayName("打开状态下 call 抛 OpenException，便于调用方区分降级与业务失败")
    void callThrowsOpenExceptionWhenOpen() {
        CircuitBreakerService service = service(0.5d, 4, 1, 1);
        open(service, "llm", 4);

        CircuitBreakerService.OpenException error = assertThrows(
                CircuitBreakerService.OpenException.class,
                () -> service.call("llm", () -> "real"));
        assertTrue(error.getMessage().contains("llm"));
    }

    @Test
    @DisplayName("冷却结束后半开，探测成功恢复 CLOSED")
    void halfOpenSuccessCloses() throws InterruptedException {
        CircuitBreakerService service = service(0.5d, 4, 1, 1);
        open(service, "llm", 4);
        assertEquals("OPEN", service.breaker("llm").state());

        Thread.sleep(1100L);
        assertEquals("real", service.call("llm", () -> "real"));

        assertEquals("CLOSED", service.breaker("llm").state());
        assertEquals(0, service.openCount());
        assertEquals(1000L, service.breaker("llm").snapshot().get("openMillis"), "关闭后冷却时间复位");
    }

    @Test
    @DisplayName("半开探测失败继续打开，冷却时间翻倍")
    void halfOpenFailureReopensWithLongerCooldown() throws InterruptedException {
        CircuitBreakerService service = service(0.5d, 4, 1, 2);
        open(service, "llm", 4);
        assertEquals("OPEN", service.breaker("llm").state());

        Thread.sleep(1100L);
        assertEquals("fallback", service.execute("llm", () -> {
            throw new IllegalStateException("still down");
        }, () -> "fallback"));

        assertEquals("OPEN", service.breaker("llm").state());
        assertEquals(2000L, service.breaker("llm").snapshot().get("openMillis"));
        assertEquals(1L, service.openedTotal(), "openTotal 统计 CLOSED→OPEN 次数，同一轮熔断不重复计数");
    }
}
