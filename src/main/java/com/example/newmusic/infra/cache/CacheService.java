package com.example.newmusic.infra.cache;

import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 二级缓存（Caffeine + Redis）与空值标记（FR-12-001 / FR-12-009）。
 *
 * <p>读：L1 → L2 → miss。写：两级同写。删：两级同删。
 *
 * <p><b>空值标记</b>（BR-12-004）：Redis 里「键不存在」和「键存在但值为
 * {@value #NULL_MARKER}」必须能区分——前者要回源，后者要立刻返回「不存在」。
 * 只用「不写缓存」来代表不存在，就等于没有任何穿透防护。
 *
 * <p><b>取出的对象视为只读。</b>L1 缓存返回的是同一个对象引用（与本项目其它读路径
 * 返回新对象的习惯不同），调用方不得修改它——这是为了不把缓存的价值耗在防御性拷贝上。
 */
@Slf4j
@Component
public class CacheService {

    /** 不存在标记。用字符串而不是 null，因为 Redis 无法区分「值就是 null」和「键不存在」 */
    public static final String NULL_MARKER = "__NULL__";

    private final RedisOps redisOps;
    private final Cache<String, Object> local;
    private final ObjectMapper objectMapper;
    private final double jitterRatio;

    /**
     * 延迟双删用的调度器。
     *
     * <p>单独一个单线程守护线程，而不是借用 Spring 的 {@code TaskScheduler}：
     * 延迟删除是「几百毫秒后删一个键」这种极轻的任务，丢进通用的调度线程池
     * 只会和定时任务抢队列，还会让它的排队延迟影响删除的准时性。
     */
    private final ScheduledExecutorService evictScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cache-delayed-evict");
        thread.setDaemon(true);
        return thread;
    });

    public CacheService(RedisOps redisOps, Cache<String, Object> local,
                        ObjectMapper objectMapper, InfraProperties properties) {
        this.redisOps = redisOps;
        this.local = local;
        this.objectMapper = objectMapper;
        this.jitterRatio = properties.getCache().getTtlJitterRatio();
    }

    /**
     * 读缓存。
     *
     * @return {@link Lookup#hit} 命中；{@link Lookup#negative} 确认不存在（负缓存命中）；两者都 false 则是 miss
     */
    public <T> Lookup<T> get(String key, Class<T> type) {
        Object localValue = local.getIfPresent(key);
        if (localValue != null) {
            return decodeLocal(localValue, type);
        }

        Optional<String> raw = redisOps.get(key);
        if (!raw.isPresent()) {
            return Lookup.miss();
        }

        Lookup<T> lookup = decodeRedis(key, raw.get(), type);
        if (lookup.isHit()) {
            local.put(key, lookup.value());
        } else if (lookup.isNegative()) {
            // 负缓存也进 L1：短时间内对同一个不存在的 ID 的重复请求连 Redis 都不用打
            local.put(key, NULL_MARKER);
        }
        return lookup;
    }

    public void put(String key, Object value, Duration ttl) {
        if (value == null) {
            return;
        }
        local.put(key, value);
        try {
            redisOps.set(key, objectMapper.writeValueAsString(value), jitter(ttl));
        } catch (Exception e) {
            log.warn("缓存序列化失败，仅写本地缓存 key={} cause={}", key, e.toString());
        }
    }

    /** 写空值缓存：TTL 必须短（默认 60s），否则数据补建后要等太久才能读到 */
    public void putNull(String key, Duration ttl) {
        local.put(key, NULL_MARKER);
        redisOps.set(key, NULL_MARKER, jitter(ttl));
    }

    /** 两级都删。写路径失效用这个 */
    public void evict(String key) {
        local.invalidate(key);
        redisOps.delete(key);
    }

    /**
     * 延迟再删一次（延迟双删的第二删，FR-12-009 / 系分 D-26）。
     *
     * <p>它补的是这个缝：第一次删除之后、新值被读到之前，某个并发请求可能已经把**旧值**
     * 重新写回缓存，此后这份旧值就会一直留到 TTL 过期。再删一次把这个窗口关上。
     *
     * <p>500ms 是权衡的结果：短于它，覆盖不到「读旧值 → 回写」的耗时；长于它，
     * 无效的窗口被拉长、多删的次数也变多。PRD 给的就是 500ms，与本实现的取舍一致。
     */
    public void evictLater(String key, Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            evictScheduler.schedule(() -> evict(key), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 应用正在关闭：此时也没有人会再读缓存了，忽略即可
            log.debug("延迟删除任务被拒绝（应用关闭中）key={}", key);
        }
    }

    /** 只删本实例的 L1（其它实例的 L1 只能等它自己过期） */
    public void evictLocal(String key) {
        local.invalidate(key);
    }

    public long ttlSeconds(String key) {
        return redisOps.ttlSeconds(key);
    }

    @PreDestroy
    public void shutdown() {
        evictScheduler.shutdownNow();
    }

    /** TTL 抖动：只在写 Redis 时生效，避免同一批 key 同时过期（系分 D-30） */
    private Duration jitter(Duration ttl) {
        if (ttl == null) {
            return null;
        }
        return RedisKeys.jitterTtl(ttl.getSeconds(), jitterRatio);
    }

    private <T> Lookup<T> decodeLocal(Object localValue, Class<T> type) {
        if (NULL_MARKER.equals(localValue)) {
            return Lookup.negative();
        }
        if (type.isInstance(localValue)) {
            return Lookup.hit(type.cast(localValue));
        }
        return Lookup.miss();
    }

    private <T> Lookup<T> decodeRedis(String key, String json, Class<T> type) {
        if (NULL_MARKER.equals(json)) {
            return Lookup.negative();
        }
        try {
            return Lookup.hit(objectMapper.readValue(json, type));
        } catch (Exception e) {
            // 脏数据（改过类结构、手工写坏）不该让接口 500，也不该算作 Redis 故障：
            // 删掉它、当作 miss 回源，让这一次请求把它重建成正确形态
            log.warn("缓存反序列化失败，按 miss 处理并清除脏数据 key={} cause={}", key, e.toString());
            redisOps.delete(key);
            return Lookup.miss();
        }
    }

    /** 缓存读取结果：命中 / 确认不存在 / 未命中，三者必须能区分。 */
    public static final class Lookup<T> {

        private static final Lookup<?> MISS = new Lookup<>(false, false, null);

        private final boolean hit;
        private final boolean negative;
        private final T value;

        private Lookup(boolean hit, boolean negative, T value) {
            this.hit = hit;
            this.negative = negative;
            this.value = value;
        }

        @SuppressWarnings("unchecked")
        public static <T> Lookup<T> miss() {
            return (Lookup<T>) MISS;
        }

        public static <T> Lookup<T> negative() {
            return new Lookup<>(false, true, null);
        }

        public static <T> Lookup<T> hit(T value) {
            return new Lookup<>(true, false, value);
        }

        /**
         * 实例方法刻意用 {@code is} 前缀：静态工厂叫 {@code hit(...)}/{@code negative()}，
         * 实例访问器若同名会与泛型静态方法擦除后签名冲突（javac 报「名称冲突」）。
         */
        public boolean isHit() {
            return hit;
        }

        public boolean isNegative() {
            return negative;
        }

        public T value() {
            return value;
        }
    }
}
