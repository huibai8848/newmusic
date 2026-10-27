package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.dataplatform.service.RealtimeMetricService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 实时看板、趋势与下钻（FR-26-005）。 */
@Api(tags = "后台接口-数据看板")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDataDashboardController {

    private final RealtimeMetricService realtimeMetricService;

    public AdminDataDashboardController(RealtimeMetricService realtimeMetricService) {
        this.realtimeMetricService = realtimeMetricService;
    }

    @ApiOperation("实时看板（12 张指标卡 + Top 内容）")
    @GetMapping("/realtime")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> realtime() {
        return R.ok(realtimeMetricService.dashboard());
    }

    @ApiOperation("指标趋势")
    @GetMapping("/trend")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> trend(@RequestParam String metricId,
                                        @RequestParam(required = false) String start,
                                        @RequestParam(required = false) String end,
                                        @RequestParam(required = false) String dimension) {
        return R.ok(realtimeMetricService.trend(metricId, start, end, dimension));
    }

    @ApiOperation("指标下钻（按维度拆分）")
    @GetMapping("/drill")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> drill(@RequestParam String metricId,
                                        @RequestParam(defaultValue = "platform") String dimension,
                                        @RequestParam(required = false) String value,
                                        @RequestParam(required = false) String date) {
        return R.ok(realtimeMetricService.drill(metricId, dimension, value, date));
    }
}
