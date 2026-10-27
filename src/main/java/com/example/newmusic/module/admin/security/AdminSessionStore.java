package com.example.newmusic.module.admin.security;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 后台会话账本。
 *
 * <p>与 C 端的 {@code TokenSessionStore} 分开：后台的失效语义更硬——禁用账号、
 * 修改密码、强制下线都要求**下一个请求立刻失效**，因此除了 refresh 轮换，
 * 还维护每管理员的会话版本号（{@code sv}）。
 *
 * <p>Redis 不可用时后台一律拒绝（fail-closed）：无法判断「是否已被踢下线」时，
 * 放行一个可能已被禁用账号的风险，比让管理员稍后重试更大。
 */
@Slf4j
@Component
public class AdminSessionStore {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final RedisOps redisOps;

    public AdminSessionStore(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    public void requireAvailable() {
        if (!redisOps.isAvailable()) {
            throw new BizException(ResultCode.CACHE_ERROR, "服务暂时不可用，请稍后再试");
        }
    }

    // ------------------------------------------------------------------ refresh

    public void registerRefresh(Long adminId, String refreshTokenId, Duration ttl) {
        redisOps.set(RedisKeys.adminRefresh(refreshTokenId), String.valueOf(adminId), ttl);
        redisOps.addToSet(RedisKeys.adminRefreshOfUser(adminId), refreshTokenId, ttl);
    }

    /**
     * 消费 refreshToken。返回 null = 不存在 / 已被用过（重放）。
     * Redis 不可用时抛 90003，绝不返回 null（否则会把「缓存故障」误判成重放）。
     */
    public Long consumeRefresh(String refreshTokenId) {
        requireAvailable();
        String key = RedisKeys.adminRefresh(refreshTokenId);
        Optional<String> value = redisOps.get(key);
        if (!value.isPresent()) {
            return null;
        }
        if (!redisOps.delete(key)) {
            return null;
        }
        Long adminId = Long.valueOf(value.get());
        redisOps.removeFromSet(RedisKeys.adminRefreshOfUser(adminId), refreshTokenId);
        return adminId;
    }

    // ------------------------------------------------------------------ 黑名单

    public void revokeAccess(String jti, long remainingSeconds) {
        if (jti == null || remainingSeconds <= 0) {
            return;
        }
        redisOps.set(RedisKeys.adminBlacklist(jti), "1", Duration.ofSeconds(remainingSeconds));
    }

    public boolean isAccessRevoked(String jti) {
        if (jti == null || !redisOps.isAvailable()) {
            return false;
        }
        return redisOps.get(RedisKeys.adminBlacklist(jti)).isPresent();
    }

    // ------------------------------------------------------------------ 会话版本

    /** 当前版本；键不存在视为 0（与首次签发一致）。Redis 不可用时抛 90003。 */
    public long currentVersion(Long adminId) {
        requireAvailable();
        Optional<String> value = redisOps.get(RedisKeys.adminSessionVersion(adminId));
        if (!value.isPresent()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 递增版本：该管理员所有已签发 Token 立刻失效，并清空 refresh 账本。 */
    public long bumpVersion(Long adminId) {
        long version = redisOps.increment(RedisKeys.adminSessionVersion(adminId), null);
        revokeAllRefresh(adminId);
        log.info("后台会话版本已递增 adminId={} version={}", adminId, version);
        return version;
    }

    public void revokeAllRefresh(Long adminId) {
        Set<String> tokenIds = redisOps.members(RedisKeys.adminRefreshOfUser(adminId));
        for (String tokenId : tokenIds) {
            redisOps.delete(RedisKeys.adminRefresh(tokenId));
        }
        if (!tokenIds.isEmpty()) {
            redisOps.delete(RedisKeys.adminRefreshOfUser(adminId));
        }
    }
}
