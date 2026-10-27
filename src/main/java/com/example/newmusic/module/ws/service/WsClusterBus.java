package com.example.newmusic.module.ws.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.ws.support.BusEnvelope;
import com.example.newmusic.module.ws.support.WsNodeId;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨实例广播出口（PRD 13 FR-13-007）。
 *
 * <p><b>为什么是 Redis Pub/Sub 而不是 PRD 写的 RocketMQ。</b>本机没有 RocketMQ
 * Broker（依赖白名单里有，但环境没装），而模块 04 的跨实例语义只需要
 * 「每个实例都收到全量消息」这一条——Redis Pub/Sub 恰好是同一模型，且 Redis
 * 本来就是前置条件。与模块 06 用 MySQL 实现 ES 的降级路径同一处理方式：
 * **这就是降级路径本身，不是脚手架**（系分 04 D-03）。换成 RocketMQ 时只需替换
 * 本类与 {@link WsBusSubscriber}，业务层一行不动。
 *
 * <p>与 RocketMQ 广播模式的差异必须写明：
 * <ul>
 *   <li><b>不持久化</b>：订阅者不在线就收不到。PRD 对 WS 推送的可靠性要求本来就是
 *       「实时性优先、离线走 HTTP 拉取」（EX-13-005-1 / BR-13-037），所以可接受；</li>
 *   <li><b>不保证顺序</b>：Redis Pub/Sub 单频道是有序的，Redisson 单连接回调也按到达顺序；
 *       跨节点同一房间的消息顺序由发布端串行与 Redis 单频道保证。控播另有 version 兜底
 *       （BR-13-021），乱序消息会被客户端丢弃。</li>
 * </ul>
 *
 * <p>发布失败**不抛出**：降级为仅本机推送 + 计数告警（EX-13-007-1）。
 */
@Slf4j
@Component
public class WsClusterBus {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final WsNodeId nodeId;
    private final WsMetrics metrics;
    /** 节流：Redis 挂掉时不要每条消息都打一行 WARN */
    private final AtomicLong lastWarnAt = new AtomicLong();

    public WsClusterBus(RedissonClient redisson, ObjectMapper objectMapper,
                        WsNodeId nodeId, WsMetrics metrics) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
        this.metrics = metrics;
    }

    /** 房间广播：本机已发过，这里只让其它节点收到。 */
    public void publishRoom(Long roomId, Map<String, Object> message, String excludeConnectionId) {
        publish(BusEnvelope.TARGET_ROOM, String.valueOf(roomId), message, excludeConnectionId);
    }

    /** 用户定向：本机已发过，这里让用户可能连接着的其它节点收到。 */
    public void publishUser(Long userId, Map<String, Object> message) {
        publish(BusEnvelope.TARGET_USER, String.valueOf(userId), message, null);
    }

    private void publish(String targetType, String targetId, Map<String, Object> message,
                         String excludeConnectionId) {
        BusEnvelope envelope = new BusEnvelope();
        envelope.setOriginNodeId(nodeId.getNodeId());
        envelope.setTargetType(targetType);
        envelope.setTargetId(targetId);
        envelope.setExcludeConnectionId(excludeConnectionId);
        envelope.setMessage(message);
        try {
            RTopic topic = redisson.getTopic(RedisKeys.WS_BUS);
            topic.publish(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            metrics.incrementBroadcastDropped();
            warnThrottled(e);
        }
    }

    private void warnThrottled(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last > 60_000L && lastWarnAt.compareAndSet(last, now)) {
            log.warn("WS 跨实例广播失败，降级为仅本机推送 cause={}（60s 内不再重复告警）", e.toString());
        }
    }
}
