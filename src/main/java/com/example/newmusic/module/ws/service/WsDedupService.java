package com.example.newmusic.module.ws.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ws.config.WsProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 可靠消息的 {@code clientMsgId} 幂等账本（BR-13-035 / EX-13-006-5）。
 *
 * <p>三态而不是两态，因为「并发重复」和「重发」的正确答案不同：
 * <ul>
 *   <li><b>首次</b>：占位成功，继续处理；</li>
 *   <li><b>重发</b>：账本里已有上一次的 ack JSON，**原样再回一次**，不重新处理；</li>
 *   <li><b>处理中</b>：账本里还是占位符。此时不能回 ack（messageId 还不存在），
 *       回 10005「请勿重复提交」，客户端按不可靠失败提示即可。</li>
 * </ul>
 *
 * <p>占位用 {@code SET NX EX}，因此两个并发请求只有一个能进入处理路径——
 * 这是「只处理一次」的硬保证，而不是靠两次 GET 中间的时间差。
 *
 * <p>Redis 不可用时 {@link RedisOps#setIfAbsent} 返回 false，会被误判为「已存在」。
 * 对幂等账本来说这是**正确方向的降级**：宁可这次不处理（客户端会收到明确的
 * 10005 而不是一条可能重复的消息），也不能在账本失效时重复广播。
 */
@Component
public class WsDedupService {

    private static final String IN_FLIGHT = "__IN_FLIGHT__";

    private final RedisOps redisOps;
    private final WsProperties properties;

    public WsDedupService(RedisOps redisOps, WsProperties properties) {
        this.redisOps = redisOps;
        this.properties = properties;
    }

    /**
     * 尝试占用一个 clientMsgId。
     *
     * @return acquired=true 表示首次、应当处理；acquired=false 时
     *         {@link Result#getPreviousAck()} 有值表示可原样回执，否则是处理中
     */
    public Result tryAcquire(Long userId, String clientMsgId) {
        if (!StringUtils.hasText(clientMsgId)) {
            // 没有 clientMsgId 就没有幂等语义：按首次处理（调用方仍会校验必填）
            return Result.acquired();
        }
        String key = RedisKeys.wsDedup(userId, clientMsgId);
        Duration ttl = Duration.ofSeconds(properties.getDedupTtlSeconds());
        boolean first = redisOps.setIfAbsent(key, IN_FLIGHT, ttl);
        if (first) {
            return Result.acquired();
        }
        String previous = redisOps.get(key).orElse(null);
        if (previous == null || IN_FLIGHT.equals(previous)) {
            return Result.inFlight();
        }
        return Result.replay(previous);
    }

    /** 处理成功后把占位符换成真正的 ack JSON。 */
    public void complete(Long userId, String clientMsgId, String ackJson) {
        if (!StringUtils.hasText(clientMsgId)) {
            return;
        }
        redisOps.set(RedisKeys.wsDedup(userId, clientMsgId), ackJson,
                Duration.ofSeconds(properties.getDedupTtlSeconds()));
    }

    /** 处理失败时释放占位，让客户端可以重试。 */
    public void release(Long userId, String clientMsgId) {
        if (!StringUtils.hasText(clientMsgId)) {
            return;
        }
        redisOps.delete(RedisKeys.wsDedup(userId, clientMsgId));
    }

    public static final class Result {

        private final boolean acquired;
        private final String previousAck;

        private Result(boolean acquired, String previousAck) {
            this.acquired = acquired;
            this.previousAck = previousAck;
        }

        static Result acquired() {
            return new Result(true, null);
        }

        static Result inFlight() {
            return new Result(false, null);
        }

        static Result replay(String previousAck) {
            return new Result(false, previousAck);
        }

        public boolean isAcquired() {
            return acquired;
        }

        public String getPreviousAck() {
            return previousAck;
        }
    }
}
