package com.example.newmusic.infra.security;

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
 * 登录态在 Redis 中的存储（FR-12-004 的会话部分）。
 *
 * <p>放在 {@code infra/security} 而不是 {@code module/user}：这是安全基础设施，
 * 需要它的有三方——JWT 过滤器（infra）、认证服务（module/user）、
 * 以及后续模块 09 的后台封禁功能（module/admin）。放在 infra 可以让三方都依赖它，
 * 而不必让 infra 反过来依赖 module。
 *
 * <p><b>降级策略是这个类的核心，不是附属品</b>（PRD §7 降级总表 / EX-12-004-1）：
 * <ul>
 *   <li>校验类（黑名单、封禁）Redis 不可用 → **放行**。可用性优先：缓存/会话服务抖动
 *       不应该让全网用户掉线；</li>
 *   <li>签发类（登录、刷新令牌）Redis 不可用 → **拒绝**（90003）。安全优先：
 *       拿不到「一次性 refresh」的账本，就无法保证轮换语义，宁可让用户稍后重试。</li>
 * </ul>
 */
@Slf4j
@Component
public class TokenSessionStore {

    /** 连续失败上限，超过即锁定（BR-12-019） */
    public static final int MAX_LOGIN_FAILURES = 5;
    /** 失败计数窗口：15 分钟 */
    public static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(15);

    private final RedisOps redisOps;

    public TokenSessionStore(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    /**
     * 签发类操作的前置检查：Redis 不可用直接拒绝。
     *
     * <p>为什么登录/刷新不能像校验那样「降级放行」：refreshToken 的轮换语义完全建立在
     * 「这个 tokenId 已经被消费掉」这份账本上。账本写不进去时签发的 refreshToken
     * 要么用不了，要么能被重复使用——后者是实打实的安全漏洞，不能靠「降级」蒙混过去。
     */
    public void requireAvailable() {
        if (!redisOps.isAvailable()) {
            throw new BizException(ResultCode.CACHE_ERROR, "服务暂时不可用，请稍后再试");
        }
    }

    // ------------------------------------------------------------------ 黑名单

    /**
     * 吊销 accessToken（登出 / 强制下线）。
     *
     * @param remainingSeconds 该 token 的剩余有效期。黑名单只需要留住「本来还有效的那段时间」，
     *                         过期后 JWT 自己会失效，再多留只是白占内存
     */
    public void revokeAccessToken(String jti, long remainingSeconds) {
        if (jti == null || remainingSeconds <= 0) {
            return;
        }
        redisOps.set(RedisKeys.blacklist(jti), "1", Duration.ofSeconds(remainingSeconds));
        log.debug("accessToken 已吊销 jti={} 剩余 {}s", jti, remainingSeconds);
    }

    public boolean isAccessTokenRevoked(String jti) {
        if (jti == null || !redisOps.isAvailable()) {
            return false;
        }
        return redisOps.get(RedisKeys.blacklist(jti)).isPresent();
    }

    // ------------------------------------------------------------------ refresh 轮换

    /** 登记一个新签发的 refreshToken（键一次性，用过即删） */
    public void registerRefresh(Long userId, String refreshTokenId, Duration ttl) {
        redisOps.set(RedisKeys.refresh(refreshTokenId), String.valueOf(userId), ttl);
        redisOps.addToSet(RedisKeys.refreshOfUser(userId), refreshTokenId, ttl);
    }

    /**
     * 消费 refreshToken（轮换）。返回 userId；返回 null 表示该 token 不可用。
     *
     * <p><b>重放判定靠 DEL 的返回值，而不是额外的锁。</b>两个并发请求都读到了同一个 tokenId，
     * 但只有其中一个能把键删掉；删不掉的那个说明有人抢先用过了——不管那是攻击者还是
     * 同用户的另一个标签页，按「可能被盗」处理都是正确的选择，所以返回 null 让调用方
     * 撤销该用户的全部会话。
     *
     * @throws BizException 90003 —— Redis 不可用。**注意这里绝不能返回 null**：
     *                      null 会被上层理解为重放，从而在缓存故障时把所有用户踢下线
     */
    public Long consumeRefresh(String refreshTokenId) {
        String key = RedisKeys.refresh(refreshTokenId);
        Optional<String> userId = redisOps.get(key);
        if (!redisOps.isAvailable()) {
            throw new BizException(ResultCode.CACHE_ERROR, "服务暂时不可用，请稍后再试");
        }
        if (!userId.isPresent()) {
            return null;
        }
        if (!redisOps.delete(key)) {
            log.warn("refreshToken 被并发使用，判定为重放 tokenId={}", refreshTokenId);
            return null;
        }
        Long uid = Long.valueOf(userId.get());
        redisOps.removeFromSet(RedisKeys.refreshOfUser(uid), refreshTokenId);
        return uid;
    }

    /**
     * 撤销某用户的全部会话（登出、或检测到 refreshToken 重放）。
     *
     * <p>用「每用户一个 tokenId 集合」而不是 {@code SCAN ml:auth:refresh:*}：
     * 后者在键多了以后是 O(全库)，而且共享 Redis 上执行 SCAN 会影响别的应用。
     */
    public void revokeAllSessions(Long userId) {
        String setKey = RedisKeys.refreshOfUser(userId);
        Set<String> tokenIds = redisOps.members(setKey);
        for (String tokenId : tokenIds) {
            redisOps.delete(RedisKeys.refresh(tokenId));
        }
        if (!tokenIds.isEmpty()) {
            redisOps.delete(setKey);
        }
        log.info("已撤销用户全部会话 userId={} 数量={}", userId, tokenIds.size());
    }

    // ------------------------------------------------------------------ 封禁

    /**
     * 打上封禁标记。
     *
     * <p>用**键的 TTL 表示封禁剩余时长**，值只存原因，不存结束时间——
     * 少一个字段就少一处格式协商，而 TTL 本身就是权威的剩余时间。
     * 永久封禁由调用方传一个足够大的 TTL（模块 09 决定）。
     */
    public void ban(Long userId, String reason, Duration ttl) {
        redisOps.set(RedisKeys.userBan(userId), reason == null ? "违反社区规范" : reason, ttl);
        // 封禁同时撤销会话：否则封禁生效前签发的 refreshToken 还能换出新 accessToken
        revokeAllSessions(userId);
        log.info("用户已被封禁 userId={} ttl={}", userId, ttl);
    }

    public void unban(Long userId) {
        redisOps.delete(RedisKeys.userBan(userId));
    }

    /** Redis 不可用时返回 false（放行）——校验类降级，见类注释 */
    public boolean isBanned(Long userId) {
        if (userId == null || !redisOps.isAvailable()) {
            return false;
        }
        return redisOps.get(RedisKeys.userBan(userId)).isPresent();
    }

    /** 取封禁原因，供 20005 的提示语使用；未封禁返回 null */
    public String banReason(Long userId) {
        if (userId == null || !redisOps.isAvailable()) {
            return null;
        }
        return redisOps.get(RedisKeys.userBan(userId)).orElse(null);
    }

    // ------------------------------------------------------------------ 登录失败锁定

    /**
     * 记一次登录失败。
     *
     * <p>计数按**账号**而不是 IP：撞库的特征是「同一账号被反复试密码」，
     * 而 IP 维度已经由限流覆盖。两者互补，不是重复。
     *
     * @return 累计失败次数；Redis 不可用时返回 0（不锁定）
     */
    public int recordLoginFailure(String account) {
        if (account == null) {
            return 0;
        }
        return (int) redisOps.increment(RedisKeys.loginFail(account), LOGIN_FAIL_WINDOW);
    }

    public boolean isLoginLocked(String account) {
        if (account == null || !redisOps.isAvailable()) {
            return false;
        }
        Optional<String> count = redisOps.get(RedisKeys.loginFail(account));
        if (!count.isPresent()) {
            return false;
        }
        try {
            return Integer.parseInt(count.get()) >= MAX_LOGIN_FAILURES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void clearLoginFailures(String account) {
        if (account != null) {
            redisOps.delete(RedisKeys.loginFail(account));
        }
    }

    /** 锁定状态下还要等多久（秒），用于提示语 */
    public long loginLockRemainingSeconds(String account) {
        long ttl = redisOps.ttlSeconds(RedisKeys.loginFail(account));
        return ttl > 0 ? ttl : 0;
    }
}
