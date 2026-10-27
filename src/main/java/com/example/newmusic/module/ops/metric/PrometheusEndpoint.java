package com.example.newmusic.module.ops.metric;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * Actuator 端点 {@code /actuator/prometheus}（管理端口 8082）。
 *
 * <p>与 {@code /api/v1/internal/ops/metrics} 共用一份 {@link OpsMetricsService} 输出。
 * 本机没有 Prometheus 服务端，所以只保证「格式正确、可被 scrape」；接上 Prometheus 时
 * 把 management 端口的地址填进 scrape_configs 即可。
 */
@Component
@Endpoint(id = "prometheus")
public class PrometheusEndpoint {

    private final OpsMetricsService metricsService;

    public PrometheusEndpoint(OpsMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @ReadOperation(produces = "text/plain;version=0.0.4;charset=utf-8")
    public String scrape() {
        return metricsService.prometheus();
    }
}
