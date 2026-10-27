package com.example.newmusic.infra.redis;

import cn.hutool.core.util.StrUtil;
import com.example.newmusic.infra.InfraProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.BaseConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis 客户端配置（模块 03）。
 *
 * <p><b>自己声明 {@link RedissonClient} 而不吃 starter 的自动配置</b>（系分 D-17）：
 * 自动配置只能把 {@code spring.redis.*} 映射过去，表达不了
 * 「重试 1 次就放弃」（故障时要快速失败，见 {@link RedisGuard}）
 * 和「单节点/集群由 {@code newmusic.infra.redis.mode} 决定」这两件事。
 * 声明自己的 Bean 后，starter 的 {@code RedissonAutoConfiguration} 会自动退让，
 * 但仍然由它提供 {@code RedissonConnectionFactory}——{@code StringRedisTemplate} 照常可用。
 *
 * <p>编解码统一用 {@link StringCodec}：本项目在 Redis 里只存 JSON 字符串，
 * 不需要 Redisson 的对象序列化。
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Redis 客户端。{@code shutdown} 交给 Spring 在关闭时调用，避免 Netty 线程池泄漏。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(InfraProperties properties) {
        InfraProperties.Redis redisProps = properties.getRedis();
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);

        String password = StrUtil.blankToDefault(redisProps.getPassword(), null);

        if (redisProps.isCluster()) {
            ClusterServersConfig cluster = config.useClusterServers()
                    .addNodeAddress(redisProps.getNodes().toArray(new String[0]))
                    .setPassword(password)
                    // 集群拓扑扫描间隔：拓扑变化后 2 秒内感知
                    .setScanInterval(2000);
            applyTimeoutSettings(cluster, redisProps);
            log.info("Redis 客户端（Cluster）初始化，nodes={}", redisProps.getNodes());
        } else {
            String address = "redis://" + redisProps.getHost() + ":" + redisProps.getPort();
            SingleServerConfig single = config.useSingleServer()
                    .setAddress(address)
                    .setDatabase(redisProps.getDatabase())
                    .setPassword(password)
                    .setConnectionPoolSize(redisProps.getConnectionPoolSize())
                    .setConnectionMinimumIdleSize(redisProps.getConnectionMinimumIdleSize());
            applyTimeoutSettings(single, redisProps);
            log.info("Redis 客户端（单节点）初始化，address={} database={} timeout={}ms retryAttempts={}",
                    address, redisProps.getDatabase(), redisProps.getTimeoutMillis(), redisProps.getRetryAttempts());
        }
        return Redisson.create(config);
    }

    /**
     * 超时与重试参数。
     *
     * <p>这些是**每个连接模式各自持有**的配置（{@code BaseConfig}），不在 {@code Config} 上，
     * 所以单节点与集群两条分支都要设一遍。
     *
     * <p>{@code retryAttempts=1} 是模块 03 特意调小的：默认 3 次重试会把「Redis 故障」
     * 的单请求代价放大三倍，而 AC-12-001-4 要求故障时响应仍 ≤200ms。
     * 快速失败 + 熔断（见 {@link RedisGuard}）比反复重试更符合这里的降级策略。
     */
    private void applyTimeoutSettings(BaseConfig<?> serverConfig, InfraProperties.Redis redisProps) {
        serverConfig.setTimeout(redisProps.getTimeoutMillis());
        serverConfig.setConnectTimeout(redisProps.getConnectTimeoutMillis());
        serverConfig.setRetryAttempts(redisProps.getRetryAttempts());
        serverConfig.setRetryInterval(redisProps.getRetryIntervalMillis());
    }

    /**
     * 一级本地缓存（Caffeine）。
     *
     * <p>它同时承担两个职责：缓存热数据、以及给 {@code DistributedLockService} 在
     * Redis 不可用时提供进程内的备用锁。
     *
     * <p>TTL 只有几十秒——本地缓存跨实例无法互相失效，时间越长一致性越差（系分 §9）。
     */
    @Bean
    public Cache<String, Object> localCache(InfraProperties properties) {
        InfraProperties.Cache cacheProps = properties.getCache();
        return Caffeine.newBuilder()
                .maximumSize(cacheProps.getLocalMaxSize())
                .expireAfterWrite(Duration.ofSeconds(cacheProps.getLocalTtlSeconds()))
                .build();
    }
}
