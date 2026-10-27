package com.example.newmusic.module.ops.controller;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.metric.OpsMetricsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * Prometheus 抓取端点（FR-28-007）。
 *
 * <p>没有引入 {@code micrometer-registry-prometheus}，所以这里直接输出 Prometheus 文本格式。
 * 该路径在 Security 里 permitAll（抓取端没有 JWT），但**不是公开接口**：
 * 来源必须在 {@code newmusic.ops.internal-ips} 白名单内（默认仅本机回环）。
 *
 * <p>为什么不放到 management 端口（8082）：management 只暴露 health，且验收脚本统一打 8081；
 * 放在业务端口上少一个端口/防火墙配置，安全由 IP 白名单保证。
 */
@Slf4j
@Api(tags = "内部接口-运维指标")
@RestController
@RequestMapping("/api/v1/internal/ops")
public class OpsInternalController {

    private final OpsMetricsService metricsService;
    private final OpsProperties properties;

    public OpsInternalController(OpsMetricsService metricsService, OpsProperties properties) {
        this.metricsService = metricsService;
        this.properties = properties;
    }

    @ApiOperation("Prometheus 文本指标")
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics(HttpServletRequest request) {
        checkSource(request);
        return metricsService.prometheus();
    }

    private void checkSource(HttpServletRequest request) {
        String ip = IpUtil.getClientIp(request);
        if (ip == null) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
        for (String allowed : properties.getInternalIps().split(",")) {
            if (allowed.trim().equals(ip)) {
                return;
            }
        }
        log.warn("内部指标接口拒绝来源 ip={}", ip);
        throw new BizException(ResultCode.NO_PERMISSION);
    }
}
