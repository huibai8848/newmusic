package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.dto.MetricApproveReq;
import com.example.newmusic.module.dataplatform.dto.MetricSaveReq;
import com.example.newmusic.module.dataplatform.entity.MetricChangeLog;
import com.example.newmusic.module.dataplatform.entity.MetricDef;
import com.example.newmusic.module.dataplatform.service.MetricService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/** 指标定义与口径审批（FR-26-003）。 */
@Api(tags = "后台接口-指标体系")
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class AdminMetricController {

    private final MetricService metricService;

    public AdminMetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @ApiOperation("指标列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<PageResult<MetricDef>> list(PageQuery query,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Integer status) {
        return R.ok(metricService.list(query, category, status));
    }

    @ApiOperation("指标详情（含最近变更）")
    @GetMapping("/{metricId}")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> detail(@PathVariable String metricId) {
        return R.ok(metricService.detail(metricId));
    }

    @ApiOperation("新建指标")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "METRIC_CREATE", target = "METRIC_DEF", description = "新建指标定义")
    public R<Map<String, Object>> create(@Valid @RequestBody MetricSaveReq req) {
        return R.ok(metricService.create(req));
    }

    @ApiOperation("修改指标（生效口径的变更需审批）")
    @PutMapping("/{metricId}")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "METRIC_UPDATE", target = "METRIC_DEF", description = "修改指标定义")
    public R<Map<String, Object>> update(@PathVariable String metricId,
                                         @Valid @RequestBody MetricSaveReq req) {
        return R.ok(metricService.update(metricId, req));
    }

    @ApiOperation("审批指标口径变更")
    @PostMapping("/{metricId}/approve")
    @PreAuthorize("hasAuthority('admin:data:approve')")
    @AdminOpLog(type = "METRIC_APPROVE", target = "METRIC_DEF", description = "审批指标口径变更")
    public R<MetricChangeLog> approve(@PathVariable String metricId,
                                      @Valid @RequestBody MetricApproveReq req) {
        return R.ok(metricService.approve(metricId, req));
    }

    @ApiOperation("指标变更历史")
    @GetMapping("/{metricId}/history")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<MetricChangeLog>> history(@PathVariable String metricId,
                                            @RequestParam(required = false) Integer status) {
        return R.ok(metricService.changeLogs(metricId, status));
    }

    @ApiOperation("下线指标")
    @DeleteMapping("/{metricId}")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "METRIC_RETIRE", target = "METRIC_DEF", description = "下线指标定义")
    public R<Void> retire(@PathVariable String metricId) {
        metricService.retire(metricId);
        return R.ok();
    }
}
