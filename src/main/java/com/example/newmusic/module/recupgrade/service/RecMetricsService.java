package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐链路指标（FR-24-011）。
 *
 * <p>在线只做「够便宜」的计数：每次召回给源记一次成功/失败与耗时，指标落 Redis Hash，
 * 按天分键、TTL 8 天。聚合报表由定时任务读取这些 Hash 后落 {@code t_rec_metric_daily}，
 * 在线请求路径上绝不做扫描或 GROUP BY。
 *
 * <p>键严格遵守 {@code ml:} 前缀；本机 Redis 是共享实例，任何清理都只碰 {@code ml:rec:*}。
 */
@Slf4j
@Service
public class RecMetricsService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Duration KEEP = Duration.ofDays(8);

    private final RedisOps redisOps;

    public RecMetricsService(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    public String today() {
        return LocalDate.now().format(DATE);
    }

    /** 记录一次召回源执行结果。 */
    public void recordRecall(String source, boolean success, long costMs, int size, String error) {
        if (source == null) {
            return;
        }
        String key = RedisKeys.recRecallStatus(today());
        try {
            Map<String, String> values = redisOps.hashGetAll(key);
            long calls = parse(values.get(source + ".calls")) + 1;
            long fail = parse(values.get(source + ".fail")) + (success ? 0 : 1);
            long empty = parse(values.get(source + ".empty")) + (success && size == 0 ? 1 : 0);
            long totalCost = parse(values.get(source + ".totalCost")) + Math.max(0, costMs);
            Map<String, String> update = new LinkedHashMap<>();
            update.put(source + ".calls", String.valueOf(calls));
            update.put(source + ".fail", String.valueOf(fail));
            update.put(source + ".empty", String.valueOf(empty));
            update.put(source + ".totalCost", String.valueOf(totalCost));
            update.put(source + ".lastCost", String.valueOf(Math.max(0, costMs)));
            update.put(source + ".lastSize", String.valueOf(size));
            update.put(source + ".lastSuccess", success ? "1" : "0");
            update.put(source + ".lastTime", String.valueOf(System.currentTimeMillis()));
            if (error != null) {
                update.put(source + ".lastError", error.length() > 200 ? error.substring(0, 200) : error);
            }
            redisOps.hashSetAll(key, update);
            redisOps.expire(key, KEEP);
        } catch (RuntimeException e) {
            // 监控不能反过来影响推荐
            log.debug("召回指标写入失败 source={} cause={}", source, e.toString());
        }
    }

    /** 当日各源指标快照。 */
    public Map<String, Map<String, Long>> recallStatus() {
        Map<String, String> raw = redisOps.hashGetAll(RedisKeys.recRecallStatus(today()));
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey();
            int dot = field.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String source = field.substring(0, dot);
            String metric = field.substring(dot + 1);
            if ("lastError".equals(metric)) {
                continue;
            }
            result.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(metric, parse(entry.getValue()));
        }
        return result;
    }

    /** 失败率超过阈值的源（监控告警用）。 */
    public List<String> unhealthySources(double threshold) {
        Map<String, Map<String, Long>> status = recallStatus();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : status.entrySet()) {
            long calls = entry.getValue().getOrDefault("calls", 0L);
            long fail = entry.getValue().getOrDefault("fail", 0L);
            if (calls >= 20 && fail / (double) calls > threshold) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public void recordDegrade(String reason) {
        String key = RedisKeys.recMetric(today(), "GLOBAL", "pipeline", "degrade");
        redisOps.increment(key, KEEP);
        String last = RedisKeys.REC_METRIC + "last:" + today();
        redisOps.set(last, reason == null ? "" : reason, Duration.ofHours(24));
    }

    public long degradeCountToday() {
        String value = redisOps.get(RedisKeys.recMetric(today(), "GLOBAL", "pipeline", "degrade"))
                .orElse("0");
        return parse(value);
    }

    private long parse(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
