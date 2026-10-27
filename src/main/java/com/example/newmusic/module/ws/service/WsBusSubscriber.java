package com.example.newmusic.module.ws.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.ws.support.BusEnvelope;
import com.example.newmusic.module.ws.support.WsNodeId;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 跨实例广播的订阅端（D-03）。
 *
 * <p>订阅在 {@code @PostConstruct} 里建立：晚于 Spring 的 bean 初始化但早于
 * 应用开始接收流量，不会漏掉启动窗口内的消息。Redisson 断线重连后会自动重新订阅，
 * 因此这里不需要自己实现重连。
 *
 * <p><b>跳过自己的消息</b>是去重的关键：{@link WsPushService} 在发布前已经投递过本机，
 * 如果订阅者不跳过，本机用户会收到两份。判断依据是信封里的 {@code originNodeId}，
 * 不是「消息内容相同」——后者在两个人同时说同一句话时会误杀。
 */
@Slf4j
@Component
public class WsBusSubscriber {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final WsPushService pushService;
    private final WsNodeId nodeId;

    private RTopic topic;
    private int listenerId = -1;

    public WsBusSubscriber(RedissonClient redisson, ObjectMapper objectMapper,
                           WsPushService pushService, WsNodeId nodeId) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
        this.nodeId = nodeId;
    }

    @PostConstruct
    public void subscribe() {
        try {
            topic = redisson.getTopic(RedisKeys.WS_BUS);
            listenerId = topic.addListener(String.class, (channel, payload) -> onMessage(payload));
            log.info("WS 跨实例广播已订阅 nodeId={} channel={}", nodeId.getNodeId(), RedisKeys.WS_BUS);
        } catch (Exception e) {
            // Redis 不可用时按 PRD §8 降级：本机通信仍可用，只是跨实例失效。
            // **不能让启动失败**——那样连本机聊天都没了。
            log.warn("WS 跨实例广播订阅失败，降级为单机模式 cause={}", e.toString());
        }
    }

    void onMessage(String payload) {
        try {
            BusEnvelope envelope = objectMapper.readValue(payload, BusEnvelope.class);
            if (nodeId.getNodeId().equals(envelope.getOriginNodeId())) {
                return;
            }
            pushService.deliverLocal(envelope);
        } catch (Exception e) {
            log.debug("WS 广播消息解析失败，已丢弃 cause={}", e.toString());
        }
    }

    @PreDestroy
    public void unsubscribe() {
        if (topic != null && listenerId >= 0) {
            try {
                topic.removeListener(listenerId);
            } catch (Exception e) {
                log.debug("WS 广播取消订阅失败 cause={}", e.toString());
            }
        }
    }
}
