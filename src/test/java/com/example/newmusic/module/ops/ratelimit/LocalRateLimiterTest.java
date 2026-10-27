package com.example.newmusic.module.ops.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单机滑动窗口限流单测（EX-28-001-2 / AC-28-001-8）。
 *
 * <p>时间由调用方传入，测试不依赖真实时钟。重点验证窗口是**闭开区间**：
 * 恰好落在 cutoff 上的旧请求会被清掉，窗口内第 limit+1 个请求被拒绝。
 */
@DisplayName("LocalRateLimiter 滑动窗口兜底")
class LocalRateLimiterTest {

    private final LocalRateLimiter limiter = new LocalRateLimiter();

    @Test
    @DisplayName("窗口内超过阈值即拒绝")
    void rejectsBeyondLimit() {
        assertTrue(limiter.tryAcquire("ip:1.1.1.1", 3, 1, 1000L));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1", 3, 1, 1001L));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1", 3, 1, 1002L));
        assertFalse(limiter.tryAcquire("ip:1.1.1.1", 3, 1, 1003L));
    }

    @Test
    @DisplayName("时间戳恰好等于 cutoff 时滑出窗口")
    void boundaryIsInclusiveOnExpiry() {
        assertTrue(limiter.tryAcquire("ip:2.2.2.2", 1, 1, 1000L));
        assertFalse(limiter.tryAcquire("ip:2.2.2.2", 1, 1, 1999L));
        // cutoff = 2000 - 1000 = 1000，ts=1000 被清理（<= cutoff）
        assertTrue(limiter.tryAcquire("ip:2.2.2.2", 1, 1, 2000L));
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void isolatesKeys() {
        assertTrue(limiter.tryAcquire("ip:a", 1, 10, 1000L));
        assertFalse(limiter.tryAcquire("ip:a", 1, 10, 1001L));
        assertTrue(limiter.tryAcquire("ip:b", 1, 10, 1001L));
    }

    @Test
    @DisplayName("retryAfterSeconds 返回队头滑出窗口的剩余秒数（向上取整，至少 1 秒）")
    void retryAfterUsesEarliestTimestamp() {
        assertTrue(limiter.tryAcquire("ip:r", 1, 10, 1000L));
        assertEquals(2L, limiter.retryAfterSeconds("ip:r", 10, 9000L));
        assertEquals(1L, limiter.retryAfterSeconds("ip:r", 10, 10000L));
        assertEquals(10L, limiter.retryAfterSeconds("unknown", 10, 9000L), "未知 key 按一个完整窗口返回");
    }

    @Test
    @DisplayName("非法参数按放行处理，避免配置错误把流量全拦住")
    void invalidRuleAllows() {
        assertTrue(limiter.tryAcquire(null, 1, 1, 1000L));
        assertTrue(limiter.tryAcquire("k", 0, 1, 1000L));
        assertTrue(limiter.tryAcquire("k", -1, 1, 1000L));
        assertTrue(limiter.tryAcquire("k", 1, 0, 1000L));
        assertEquals(0, limiter.size(), "非法请求不创建窗口");
    }

    @Test
    @DisplayName("cleanup 不会误删仍有记录的窗口")
    void cleanupKeepsNonEmptyWindows() {
        assertTrue(limiter.tryAcquire("ip:c", 1, 1, 1000L));
        assertEquals(0, limiter.cleanup(), "窗口里还有时间戳时不能删");
        assertEquals(1, limiter.size());

        // 过期后的第一次判定清掉旧时间戳、写入新时间戳，队列仍然非空
        assertTrue(limiter.tryAcquire("ip:c", 1, 1, 2001L));
        assertEquals(0, limiter.cleanup());
    }
}
