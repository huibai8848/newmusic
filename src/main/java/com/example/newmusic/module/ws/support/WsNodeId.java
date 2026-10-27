package com.example.newmusic.module.ws.support;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.module.ws.config.WsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * 当前 WS 实例的节点标识。
 *
 * <p>多实例部署时必须显式配置 {@code newmusic.ws.node-id}（或 {@code WS_NODE_ID}），
 * 否则每次重启都会换一个 ID：旧节点登记在 Redis 里的负载键要等 TTL 才消失，
 * 监控上会短暂出现幽灵节点。单机开发留空即可，随机 ID 反而避免两个本地实例撞名。
 */
@Slf4j
@Component
public class WsNodeId {

    private final String nodeId;

    public WsNodeId(WsProperties properties) {
        String configured = properties.getNodeId();
        if (configured != null && !configured.trim().isEmpty()) {
            this.nodeId = configured.trim();
        } else {
            // 8 位足够区分本机同时起的多个实例；不追求全局唯一（Redis 键里还会带连接序号）
            this.nodeId = "n" + IdUtil.fastSimpleUUID().substring(0, 8);
        }
        log.info("WS 节点标识 nodeId={} pid={}", nodeId, pid());
    }

    public String getNodeId() {
        return nodeId;
    }

    /** 连接 ID：{@code nodeId-s序号}，与 PRD 的 {@code n1-s12345} 同构。 */
    public String connectionId(long sequence) {
        return nodeId + "-s" + sequence;
    }

    private static String pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int idx = name.indexOf('@');
        return idx > 0 ? name.substring(0, idx) : name;
    }
}
