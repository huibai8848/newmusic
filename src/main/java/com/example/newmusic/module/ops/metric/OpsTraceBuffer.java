package com.example.newmusic.module.ops.metric;

import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.service.OpsConfigService;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内链路记录（FR-28-008 / AC-28-008-1~6）。
 *
 * <p>本机没有 SkyWalking / Zipkin，也没有 OTel 依赖。这里退一步：每个请求结束时把
 * traceId、路由、耗时、状态码、用户、错误摘要写进固定长度的环形缓冲，后台按
 * traceId / 用户 / 路径 / 耗时检索。它不是全链路追踪，但能回答运维最常问的三个问题：
 * 「这次失败的 traceId 是什么」「这个用户刚才调了什么」「哪条路由慢了」。
 *
 * <p>采样策略：5xx 与 429 **全量保留**（排障必须），2xx 按 {@code ops.trace.sample-rate}
 * 采样（默认 0.1），避免高 QPS 下环形缓冲被正常请求瞬间冲掉。缓冲区满时覆盖最旧的记录，
 * 内存占用恒定（默认 2000 条 × 约 200B）。
 */
@Component
public class OpsTraceBuffer {

    private final OpsProperties properties;
    private final OpsConfigService configService;

    private final TraceRecord[] buffer;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();

    public OpsTraceBuffer(OpsProperties properties, OpsConfigService configService) {
        this.properties = properties;
        this.configService = configService;
        int size = Math.max(100, Math.min(20000, properties.getTraceBufferSize()));
        this.buffer = new TraceRecord[size];
    }

    public boolean shouldRecord(int status) {
        if (status >= 400) {
            return true;
        }
        double rate = configService.getDouble(OpsConfigService.KEY_TRACE_SAMPLE_RATE,
                properties.getTraceSampleRate());
        if (rate <= 0d) {
            return false;
        }
        if (rate >= 1d) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    public void record(String traceId, String method, String path, int status,
                       long durationMs, Long userId, String ip, String error) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        TraceRecord record = new TraceRecord();
        record.traceId = traceId.length() > 64 ? traceId.substring(0, 64) : traceId;
        record.timestamp = System.currentTimeMillis();
        record.method = method;
        record.path = path != null && path.length() > 300 ? path.substring(0, 300) : path;
        record.status = status;
        record.durationMs = durationMs;
        record.userId = userId;
        record.ip = ip;
        record.error = error != null && error.length() > 300 ? error.substring(0, 300) : error;
        long seq = sequence.incrementAndGet();
        record.seq = seq;
        synchronized (buffer) {
            buffer[(int) Math.floorMod(seq, buffer.length)] = record;
        }
        recorded.incrementAndGet();
    }

    public void markDropped() {
        dropped.incrementAndGet();
    }

    /** 最新在前；所有过滤条件都是 AND，空值忽略。 */
    public List<TraceRecord> search(String traceId, Long userId, String path,
                                    Integer status, Long minDurationMs, int limit) {
        int max = Math.max(1, Math.min(500, limit));
        List<TraceRecord> all = new ArrayList<>(buffer.length);
        synchronized (buffer) {
            for (TraceRecord item : buffer) {
                if (item != null) {
                    all.add(item);
                }
            }
        }
        all.sort((a, b) -> Long.compare(b.seq, a.seq));
        List<TraceRecord> result = new ArrayList<>();
        for (TraceRecord item : all) {
            if (result.size() >= max) {
                break;
            }
            if (StringUtils.hasText(traceId) && (item.traceId == null
                    || !item.traceId.toLowerCase().startsWith(traceId.toLowerCase()))) {
                continue;
            }
            if (userId != null && !userId.equals(item.userId)) {
                continue;
            }
            if (StringUtils.hasText(path) && (item.path == null
                    || !item.path.toLowerCase().contains(path.toLowerCase()))) {
                continue;
            }
            if (status != null && status != item.status) {
                continue;
            }
            if (minDurationMs != null && item.durationMs < minDurationMs) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> stats() {
        int filled = 0;
        synchronized (buffer) {
            for (TraceRecord item : buffer) {
                if (item != null) {
                    filled++;
                }
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capacity", buffer.length);
        map.put("filled", filled);
        map.put("recorded", recorded.get());
        map.put("dropped", dropped.get());
        map.put("sampleRate", configService.getDouble(OpsConfigService.KEY_TRACE_SAMPLE_RATE,
                properties.getTraceSampleRate()));
        map.put("startedAt", startedAt);
        return map;
    }

    public void clear() {
        synchronized (buffer) {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = null;
            }
        }
        recorded.set(0);
        dropped.set(0);
    }

    @Getter
    public static class TraceRecord {
        private long seq;
        private String traceId;
        private long timestamp;
        private String method;
        private String path;
        private int status;
        private long durationMs;
        private Long userId;
        private String ip;
        private String error;
    }
}
