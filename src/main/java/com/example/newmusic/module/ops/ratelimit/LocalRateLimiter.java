package com.example.newmusic.module.ops.ratelimit;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机滑动窗口限流（EX-28-001-2 / AC-28-001-8）。
 *
 * <p>Redis 不可用时，全局限流不能退化成「不限流」——那会让故障瞬间的流量直接砸到 DB。
 * 这里用进程内的精确滑动窗口兜底：精度只在单实例内成立，但足以保护本机。
 *
 * <p>实现是「按 key 的请求时间戳双端队列 + 惰性清理」，没有后台线程：
 * 每次判定时把滑出窗口的时间戳从队头弹掉。队列长度天然不会超过阈值，
 * 因此内存上界 = 阈值 × key 数，不需要额外的容量保护。
 */
@Component
public class LocalRateLimiter {

    private static final int MAX_KEYS = 20000;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @return true 放行；false 超限
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds, long now) {
        if (key == null || limit <= 0 || windowSeconds <= 0) {
            return true;
        }
        if (windows.size() > MAX_KEYS && !windows.containsKey(key)) {
            // 极端情况下（大量伪造 IP）限流表本身不能变成内存炸弹：直接放行并让 Redis 路径接管
            return true;
        }
        long windowMillis = windowSeconds * 1000L;
        Deque<Long> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            long cutoff = now - windowMillis;
            Iterator<Long> iterator = deque.iterator();
            while (iterator.hasNext()) {
                Long ts = iterator.next();
                if (ts == null || ts <= cutoff) {
                    iterator.remove();
                } else {
                    break;
                }
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** 超限时还要多久（秒）：队头滑出窗口的时刻。 */
    public long retryAfterSeconds(String key, int windowSeconds, long now) {
        Deque<Long> deque = windows.get(key);
        if (deque == null) {
            return windowSeconds;
        }
        synchronized (deque) {
            Long earliest = deque.peekFirst();
            if (earliest == null) {
                return windowSeconds;
            }
            long wait = earliest + windowSeconds * 1000L - now;
            return Math.max(1, (wait + 999) / 1000);
        }
    }

    /** 定期清理空队列，避免 key 长期占用内存。 */
    public int cleanup() {
        int removed = 0;
        for (Map.Entry<String, Deque<Long>> entry : windows.entrySet()) {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                if (deque.isEmpty()) {
                    windows.remove(entry.getKey(), deque);
                    removed++;
                }
            }
        }
        return removed;
    }

    public int size() {
        return windows.size();
    }
}
