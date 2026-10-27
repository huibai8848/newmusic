package com.example.newmusic.infra;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基础设施配置（模块 03）。对应 {@code newmusic.infra.*}。
 *
 * <p>集中在一个类里而不是拆成五六个 {@code @ConfigurationProperties}：这些值都很小，
 * 拆开只会让「Redis 相关的旋钮都在哪」变得难找。
 *
 * <p>注意前缀是 {@code newmusic.infra} 而非 {@code newmusic}：后者已经被
 * {@code newmusic.media.*}（模块 07）占用，绑到同一棵树会把不相干的配置也吸进来。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.infra")
public class InfraProperties {

    private Redis redis = new Redis();
    private Cache cache = new Cache();
    private Lock lock = new Lock();
    private Guard guard = new Guard();
    private Limit limit = new Limit();

    @Data
    public static class Redis {
        /** single | cluster（系分 D-17） */
        private String mode = "single";
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        /** mode=cluster 时的节点列表，形如 redis://10.0.1.10:7000 */
        private List<String> nodes = new ArrayList<>();
        private int timeoutMillis = 1000;
        private int connectTimeoutMillis = 1000;
        private int retryAttempts = 1;
        private int retryIntervalMillis = 100;
        private int connectionPoolSize = 32;
        private int connectionMinimumIdleSize = 4;

        public boolean isCluster() {
            return "cluster".equalsIgnoreCase(mode);
        }
    }

    @Data
    public static class Cache {
        /** 歌曲详情缓存 TTL（秒） */
        private long songTtlSeconds = 1800;
        /** 空值缓存 TTL（秒），防穿透 */
        private long nullTtlSeconds = 60;
        /** TTL 抖动比例，只加不减（系分 D-30） */
        private double ttlJitterRatio = 0.1;
        private long localTtlSeconds = 30;
        private long localMaxSize = 10_000;
        private Bloom bloom = new Bloom();

        @Data
        public static class Bloom {
            private boolean enabled = true;
            private long expectedInsertions = 10_000_000L;
            private double falseProbability = 0.01;
        }
    }

    @Data
    public static class Lock {
        private long waitSeconds = 2;
        private long leaseSeconds = 5;
    }

    @Data
    public static class Guard {
        private int failureThreshold = 3;
        private long openMillis = 5000;
    }

    @Data
    public static class Limit {
        /** IP 白名单，逗号分隔（配置里用字符串更方便，绑定后按逗号拆成 List 由 Spring 完成） */
        private List<String> whitelist = new ArrayList<>();
    }
}
