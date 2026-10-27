package com.example.newmusic.infra.cache;

import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.redis.RedisGuard;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 布隆过滤器封装（FR-12-009 9.1），用于在回源之前挡掉「一定不存在」的 ID。
 *
 * <p>与空值缓存的分工：空值缓存挡的是**同一个**不存在的 ID 被反复请求；
 * 布隆过滤器挡的是**大量各不相同的**随机 ID——后者会让空值缓存逐个写入，
 * 等于把穿透原样转发成了缓存写入。
 *
 * <p><b>误判方向决定了降级方向。</b>布隆过滤器只有假阳性、没有假阴性：
 * 「说存在」可能是错的，「说不存在」一定是对的。所以
 * <ul>
 *   <li>未启用 / 未初始化 / Redis 故障 → {@link #mightContain} 返回 <b>true</b>（不确定就放行）；</li>
 *   <li>返回 false 才允许跳过 DB 直接判 404。</li>
 * </ul>
 * 反过来做（故障时返回 false）会把整个曲库变成「不存在」。
 *
 * <p>另一个必须接受的性质：**布隆过滤器不支持删除**。歌曲下架/删除后它的 ID 仍在过滤器里，
 * 于是会多回源一次 DB，然后被空值缓存接住。这是可接受的代价——用「偶尔多查一次库」
 * 换「不会误杀存在的歌曲」。
 */
@Slf4j
@Component
public class BloomSupport {

    private final RedissonClient redisson;
    private final RedisGuard guard;
    private final boolean enabled;
    private final long expectedInsertions;
    private final double falseProbability;

    private final ConcurrentHashMap<String, RBloomFilter<String>> filters = new ConcurrentHashMap<>();

    public BloomSupport(RedissonClient redisson, RedisGuard guard, InfraProperties properties) {
        this.redisson = redisson;
        this.guard = guard;
        InfraProperties.Cache.Bloom bloom = properties.getCache().getBloom();
        this.enabled = bloom.isEnabled();
        this.expectedInsertions = bloom.getExpectedInsertions();
        this.falseProbability = bloom.getFalseProbability();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void add(String key, String value) {
        if (!enabled || value == null) {
            return;
        }
        guard.run("BLOOM_ADD " + key, () -> filter(key).add(value));
    }

    /**
     * @return false 表示**一定不存在**（可以安全地跳过 DB）；true 表示可能存在或无法判断
     */
    public boolean mightContain(String key, String value) {
        if (!enabled || value == null || !guard.isAvailable()) {
            return true;
        }
        Boolean contains = guard.call("BLOOM_CONTAINS " + key, () -> filter(key).contains(value), null);
        // contains 返回 null 说明脚本/连接出错，与「不确定」同义：放行
        return contains == null || contains;
    }

    /** 惰性取过滤器并保证已初始化。concurrent 场景下 tryInit 只会有一个成功，另一个拿到 false 也无妨 */
    private RBloomFilter<String> filter(String key) {
        return filters.computeIfAbsent(key, k -> {
            RBloomFilter<String> filter = redisson.getBloomFilter(k, StringCodec.INSTANCE);
            if (!filter.isExists()) {
                boolean created = filter.tryInit(expectedInsertions, falseProbability);
                log.info("布隆过滤器初始化 key={} 容量={} 误判率={} 新建={}",
                        k, expectedInsertions, falseProbability, created);
            }
            return filter;
        });
    }
}
