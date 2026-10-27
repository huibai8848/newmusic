package com.example.newmusic.module.recommend.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 推荐结果的 Redis JSON 缓存。
 *
 * <p>不复用 {@code CacheService} 的 Caffeine L1：推荐缓存需要按前缀批量失效
 * （问卷提交、反馈撤销都会影响整类结果），本地 L1 在其它实例上仍然会读到旧值，
 * 而给每个实例发失效消息的成本远大于收益。推荐接口的 P95 目标由 Redis 读满足。
 */
@Slf4j
@Component
public class RecommendCache {

    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;

    public RecommendCache(RedisOps redisOps, ObjectMapper objectMapper) {
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Optional<String> raw = redisOps.get(key);
        if (!raw.isPresent()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(raw.get(), type));
        } catch (Exception e) {
            log.warn("推荐缓存反序列化失败，按未命中处理 key={} cause={}", key, e.toString());
            redisOps.delete(key);
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        if (value == null) {
            return;
        }
        try {
            redisOps.set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("推荐缓存序列化失败 key={} cause={}", key, e.toString());
        }
    }

    public void delete(String key) {
        redisOps.delete(key);
    }

    public void deleteByPrefix(String prefix) {
        try {
            redisOps.deleteByPrefix(prefix);
        } catch (RuntimeException e) {
            log.warn("推荐缓存前缀失效失败 prefix={} cause={}", prefix, e.toString());
        }
    }

    /** 用户画像/反馈变化后，清掉该用户的每日、猜你喜欢、新人缓存。 */
    public void invalidateUser(Long userId) {
        if (userId == null) {
            return;
        }
        deleteByPrefix(RedisKeys.REC_DAILY + userId + ":");
        delete(RedisKeys.recGuess(userId));
        delete(RedisKeys.recNewcomer(userId));
    }
}
