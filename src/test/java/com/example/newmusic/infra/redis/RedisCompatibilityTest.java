package com.example.newmusic.infra.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redisson 3.17.7 能否驱动**本机 Redis 3.0.504**（Windows 老版本，PRD 要求 6.0+）。
 *
 * <p>存在的理由：模块 03 的分布式锁与限流全部押在 Redisson 上，而本机 Redis 比 PRD 目标版本
 * 落后三个大版本。若这里不兼容，整份设计要退回「RedisTemplate + 手写 Lua 锁」，所以把它做成
 * 可重复执行的回归测试，而不是一次性的人工试探。
 *
 * <p><b>刻意不依赖 Spring</b>（纯 JUnit + Redisson API）：这样即使 Spring 上下文或数据库起不来，
 * 也能单独回答「驱动能不能用」这一个问题。
 *
 * <p><b>固定用 db1</b>：本机 6379 是共享实例，db0 里躺着另一个项目的 {@code ranking:*} 键。
 * 测试自建自清，绝不触碰 db0。
 *
 * <p>需要本机 6379 有 Redis 在跑；这是集成测试，不同于同目录下「零依赖」的纯函数测试。
 */
@DisplayName("Redisson ↔ Redis 3.0.504 兼容性")
class RedisCompatibilityTest {

    /** db1：与共享实例上另一个项目的 db0 隔离 */
    private static final int PROBE_DB = 1;
    private static final String PREFIX = "ml:probe:";

    private static RedissonClient client;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setDatabase(PROBE_DB)
                .setTimeout(3000)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        client = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        // 只删自己建的键；不用 flushDb，以免误伤同库里别的东西
        client.getKeys().deleteByPattern(PREFIX + "*");
        client.shutdown();
    }

    @Test
    @DisplayName("连接与基础命令可用")
    void pingWorks() {
        long pong = client.getKeys().count();
        assertTrue(pong >= 0, "getKeys().count() 应可执行");
    }

    @Test
    @DisplayName("RLock：tryLock / isHeldByCurrentThread / unlock")
    void lockWorks() throws InterruptedException {
        RLock lock = client.getLock(PREFIX + "lock");
        boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
        assertTrue(acquired, "应当获取到锁");
        assertTrue(lock.isHeldByCurrentThread(), "当前线程应持有锁");
        lock.unlock();
        assertFalse(lock.isLocked(), "unlock 后锁应释放");
    }

    @Test
    @DisplayName("RLock：leaseTime 到期自动释放（不靠看门狗）")
    void lockAutoReleases() throws InterruptedException {
        RLock lock = client.getLock(PREFIX + "lease");
        assertTrue(lock.tryLock(1, 1, TimeUnit.SECONDS));
        // Redisson 在指定 leaseTime 时不启动看门狗，1 秒后应自动释放
        long deadline = System.currentTimeMillis() + 8000;
        while (lock.isLocked() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertFalse(lock.isLocked(), "leaseTime=1s 的锁应在到期后释放，否则模块 03 会死锁");
    }

    @Test
    @DisplayName("RMap 与 RAtomicLong：计数用的 HINCRBY / INCR 语义")
    void counterWorks() {
        RMap<String, String> map = client.getMap(PREFIX + "stat", StringCodec.INSTANCE);
        map.clear();
        map.addAndGet("like", 1L);
        map.addAndGet("like", 1L);
        map.addAndGet("like", -1L);
        assertEquals("1", map.get("like"), "Hash 计数应支持增减");

        RAtomicLong counter = client.getAtomicLong(PREFIX + "incr");
        counter.set(0);
        assertEquals(1, counter.incrementAndGet());
        assertEquals(6, counter.addAndGet(5));
        counter.delete();
    }

    @Test
    @DisplayName("RBloomFilter：tryInit / add / contains（PRD FR-12-009 的穿透防护）")
    void bloomFilterWorks() {
        RBloomFilter<String> filter = client.getBloomFilter(PREFIX + "bloom");
        filter.delete();
        assertTrue(filter.tryInit(1000L, 0.01), "tryInit 应成功");
        filter.add("1000000000000005001");
        assertTrue(filter.contains("1000000000000005001"), "已加入的元素必须命中");
        // 布隆过滤器只保证「不存在的一定判不存在」
        assertFalse(filter.contains("9999999999999999999"), "未加入的元素不应命中");
    }

    @Test
    @DisplayName("滑动窗口限流 Lua（PRD FR-12-005 原样脚本）可在 3.0 上原子执行")
    void slidingWindowLuaWorks() {
        // 与 PRD 完全一致，只把 KEY 前缀换成本测试的 probe 前缀
        String lua = "local key = KEYS[1]\n"
                + "local window = tonumber(ARGV[1])\n"
                + "local limit = tonumber(ARGV[2])\n"
                + "local now = tonumber(ARGV[3])\n"
                + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
                + "local count = redis.call('ZCARD', key)\n"
                + "if count < limit then\n"
                + "    redis.call('ZADD', key, now, now .. '-' .. math.random(100000))\n"
                + "    redis.call('PEXPIRE', key, window)\n"
                + "    return 1\n"
                + "else\n"
                + "    return 0\n"
                + "end";

        String key = PREFIX + "limit";
        client.getBucket(key).delete();
        RScript script = client.getScript(StringCodec.INSTANCE);
        long now = System.currentTimeMillis();
        int limit = 3;

        for (int i = 1; i <= limit; i++) {
            assertEquals(1L, evalAllow(script, lua, key, limit, now + i),
                    "窗口内第 " + i + " 次应放行");
        }
        // 第 limit+1 次应被拒
        assertEquals(0L, evalAllow(script, lua, key, limit, now + 10), "超过阈值应被拒绝");

        // 窗口滑过之后（时间前进 61s），旧记录被 ZREMRANGEBYSCORE 清掉，应重新放行
        assertEquals(1L, evalAllow(script, lua, key, limit, now + 61_000), "窗口滑过后应重新放行");

        // PEXPIRE 生效：键有 TTL（允许 1 秒误差，避免刚写入时读到 59.9s）
        long ttlSeconds = client.getBucket(key).remainTimeToLive() / 1000;
        assertTrue(Math.abs(ttlSeconds - 60) <= 1, "限流键 TTL 应约等于窗口长度，实际 " + ttlSeconds + "s");
    }

    /**
     * 执行限流脚本。注意 {@code RScript.eval} 的签名是
     * {@code eval(Mode, String, ReturnType, List<Object> keys, Object... values)}，
     * 最后一段是**变长参数**——若把一个 {@code List} 当单个值传进去，脚本里
     * {@code ARGV[3]} 会是 nil（实测报 "attempt to perform arithmetic on local 'now'"）。
     */
    private static long evalAllow(RScript script, String lua, String key, int limit, long nowMillis) {
        Long result = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                Collections.singletonList(key),
                "60000", String.valueOf(limit), String.valueOf(nowMillis));
        assertTrue(result != null, "脚本应返回 1 或 0，而不是 nil");
        return result;
    }
}
