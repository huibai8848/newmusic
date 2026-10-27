package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.module.ops.entity.MetricSnapshot;
import com.example.newmusic.module.ops.mapper.MetricSnapshotMapper;
import com.example.newmusic.module.ops.metric.OpsMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标落库（趋势与验收，FR-28-007）。
 *
 * <p>环内指标只保留最近 5 分钟窗口，进程重启即清零；这里每分钟把关键指标写进
 * {@code t_ops_metric_snapshot}，让「昨天 14:00 的 p95」这种问题有地方可查。
 * 保留期 7 天，由 {@link MetricSnapshotMapper#deleteBefore} 带时间条件清理。
 */
@Slf4j
@Service
public class MetricSnapshotService {

    private static final int RETENTION_DAYS = 7;

    private final MetricSnapshotMapper mapper;
    private final OpsMetricsService metricsService;
    private final OpsConfigService configService;

    public MetricSnapshotService(MetricSnapshotMapper mapper, OpsMetricsService metricsService,
                                 OpsConfigService configService) {
        this.mapper = mapper;
        this.metricsService = metricsService;
        this.configService = configService;
    }

    @Scheduled(fixedDelayString = "${newmusic.ops.snapshot-interval-ms:60000}")
    public void scheduledSnapshot() {
        if (!configService.getBool(OpsConfigService.KEY_METRICS_SNAPSHOT_ENABLED, true)) {
            return;
        }
        try {
            snapshot();
        } catch (Exception e) {
            log.warn("指标落库失败 cause={}", e.toString());
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> current = metricsService.snapshot();
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("qps", number(current.get("qps")));
        values.put("p95_ms", number(current.get("p95Ms")));
        values.put("p99_ms", number(current.get("p99Ms")));
        values.put("error_rate_60s", number(current.get("errorRate60s")));
        values.put("degrade_level", number(current.get("degradeLevel")));
        values.put("heap_usage_ratio", metricsService.heapUsageRatio());
        values.put("ws_connections", number(current.get("wsConnections")));
        values.put("rate_limited_60s", number(current.get("rateLimited60s")));
        values.put("slow_sql_60s", number(current.get("slowSql60s")));

        LocalDateTime now = LocalDateTime.now();
        String tags = "instance=local";
        List<MetricSnapshot> saved = new ArrayList<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            MetricSnapshot snapshot = new MetricSnapshot();
            snapshot.setMetricName(entry.getKey());
            snapshot.setMetricValue(entry.getValue());
            snapshot.setTags(tags);
            snapshot.setSnapshotTime(now);
            mapper.insert(snapshot);
            saved.add(snapshot);
        }
        // 每分钟一次清理太浪费；只在每天 03:00 的批次里删过期数据
        if (now.getHour() == 3 && now.getMinute() == 0) {
            int deleted = mapper.deleteBefore(now.minusDays(RETENTION_DAYS));
            log.info("指标快照清理 deleted={}", deleted);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved.size());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    public List<Map<String, Object>> aggregate(int minutes) {
        return mapper.aggregateRecent(Math.max(1, Math.min(1440, minutes)));
    }

    public List<MetricSnapshot> history(String metricName, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getMetricName, metricName)
                .orderByDesc(MetricSnapshot::getSnapshotTime)
                .last("LIMIT " + Math.max(1, Math.min(500, limit))));
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }
}
