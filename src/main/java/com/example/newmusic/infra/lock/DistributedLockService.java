package com.example.newmusic.infra.lock;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.redis.RedisGuard;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 分布式锁（FR-12-006）。
 *
 * <p>基于 Redisson {@link RLock}。三条来自 PRD 的硬约束在这里落地：
 * <ul>
 *   <li><b>必须显式指定等待时间与持有时间</b>（BR-12-006-002/003）。指定了 leaseTime
 *       就不会启用看门狗——持有者宕机时锁必定在 leaseTime 后自动释放，不会死锁。
 *       反向的代价是：业务执行时间超过 leaseTime 时锁会提前失效，所以 leaseTime
 *       必须显著大于锁内操作耗时（缓存重建是「一次 DB 查询」，5 秒足够）；</li>
 *   <li><b>锁内操作必须幂等</b>：缓存重建天然幂等；</li>
 *   <li><b>释放前确认持有者是自己</b>：{@code isHeldByCurrentThread}，
 *       否则 leaseTime 到期后被别人拿走，我们的 unlock 会把别人的锁删掉。</li>
 * </ul>
 *
 * <p><b>Redis 不可用时降级为进程内 {@link ReentrantLock}</b>（系分 D-21）。
 * 降级的取舍值得写清楚：直接「放行不加锁」会让所有线程同时回源，正是击穿本身；
 * 直接报错则把「缓存抖动」升级成「功能不可用」。进程内锁在单实例下仍然挡住了击穿，
 * 多实例下退化为「每实例一个回源者」——比前两者都更接近原意，且故障恢复后自动回到分布式锁。
 */
@Slf4j
@Component
public class DistributedLockService {

    private final RedissonClient redisson;
    private final RedisGuard guard;
    private final long waitSeconds;
    private final long leaseSeconds;

    /** 降级用的进程内锁。Caffeine 承载是为了给锁对象设一个自动回收的期限，避免 map 无限增长 */
    private final Cache<String, ReentrantLock> localLocks = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();

    public DistributedLockService(RedissonClient redisson, RedisGuard guard, InfraProperties properties) {
        this.redisson = redisson;
        this.guard = guard;
        this.waitSeconds = properties.getLock().getWaitSeconds();
        this.leaseSeconds = properties.getLock().getLeaseSeconds();
    }

    /**
     * 尝试加锁后执行。
     *
     * <p><b>等不到锁也照常执行 action。</b>这是缓存重建场景要的语义：宁可这个请求多查一次库，
     * 也不能因为抢锁失败就把接口打成错误。真正防止击穿的是「拿到锁的那个请求会重建缓存，
     * 其余请求在等待期间大概率直接命中已建好的缓存」。
     */
    public <T> T execute(String key, Supplier<T> action) {
        try (LockHandle handle = acquire(key)) {
            return action.get();
        }
    }

    /**
     * 必须持有锁才能执行，等不到锁抛 {@code 90001}（PRD EX-12-006-1）。
     *
     * <p>用于「重复执行会产生副作用」的场景（下单、结算类）。缓存重建不属于此类。
     *
     * <p>注意：PRD 该条写的错误码是 90005，但其括注是「系统繁忙」，而 90005 在
     * 错误码表里的语义是「服务已降级」，自相矛盾；按语义取 90001（系分 D-25）。
     */
    public <T> T executeOrFail(String key, Supplier<T> action) {
        try (LockHandle handle = acquire(key)) {
            if (!handle.locked()) {
                throw new BizException(ResultCode.SYSTEM_BUSY, "系统繁忙，请稍后再试");
            }
            return action.get();
        }
    }

    private LockHandle acquire(String key) {
        // guard.call 在熔断打开时直接返回 fallback（null），不会真去等 Redis
        LockHandle remote = guard.call("LOCK " + key, () -> tryRemoteLock(key), null);
        if (remote != null) {
            return remote;
        }
        return acquireLocalLock(key);
    }

    private LockHandle tryRemoteLock(String key) {
        try {
            RLock lock = redisson.getLock(key);
            if (lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
                return new RemoteHandle(lock);
            }
            log.debug("等待 {}s 未获取到分布式锁，按未加锁继续 key={}", waitSeconds, key);
            return LockHandle.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LockHandle.NONE;
        }
    }

    private LockHandle acquireLocalLock(String key) {
        ReentrantLock local = localLocks.get(key, k -> new ReentrantLock());
        try {
            if (local.tryLock(waitSeconds, TimeUnit.SECONDS)) {
                log.debug("Redis 不可用，已降级为进程内锁 key={}", key);
                return new LocalHandle(local);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return LockHandle.NONE;
    }

    /** 锁句柄：{@code locked()} 回答「这次到底有没有锁」，{@code close()} 负责释放。 */
    private interface LockHandle extends AutoCloseable {

        LockHandle NONE = new LockHandle() {
            @Override
            public boolean locked() {
                return false;
            }

            @Override
            public void close() {
                // 什么都没锁，自然什么都不用放
            }
        };

        boolean locked();

        @Override
        void close();
    }

    private static final class RemoteHandle implements LockHandle {

        private final RLock lock;

        RemoteHandle(RLock lock) {
            this.lock = lock;
        }

        @Override
        public boolean locked() {
            return true;
        }

        @Override
        public void close() {
            // 必须先确认持有者是自己：leaseTime 到期后锁可能已被别人取走，
            // 此时 unlock 会抛 IllegalMonitorStateException，更糟的是删掉别人的锁
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("释放分布式锁失败（锁可能已因 lease 到期自动释放）: {}", e.toString());
            }
        }
    }

    private static final class LocalHandle implements LockHandle {

        private final ReentrantLock lock;

        LocalHandle(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public boolean locked() {
            return true;
        }

        @Override
        public void close() {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
