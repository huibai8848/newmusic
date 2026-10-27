package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.service.ReportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 运营报表（FR-26-009）。
 *
 * <p><b>路径偏差</b>：PRD 26 的接口清单写的是 {@code /api/v1/admin/reports/{type}}，
 * 但模块 09 的 {@code AdminReportController} 已占用 {@code /api/v1/admin/reports}（模内容举报）。
 * 两个 {@code GET /{id}} 会产生歧义映射，因此本模块改用 {@code /api/v1/admin/data-reports}。
 * 语义不变，权限点不变。
 */
@Api(tags = "后台接口-数据报表")
@RestController
@RequestMapping("/api/v1/admin/data-reports")
public class AdminDataReportController {

    private final ReportService reportService;

    public AdminDataReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @ApiOperation("报表类型清单")
    @GetMapping("/types")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, String>> types() {
        return R.ok(ReportService.TYPES);
    }

    @ApiOperation("报表列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String start,
                                             @RequestParam(required = false) String end) {
        return R.ok(reportService.list(type, start, end));
    }

    @ApiOperation("我的报表订阅")
    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> mySubscription() {
        return R.ok(reportService.mySubscription());
    }

    @ApiOperation("保存报表订阅")
    @PostMapping("/subscription")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> subscribe(@RequestParam String reportTypes,
                                            @RequestParam(defaultValue = "DAILY") String frequency,
                                            @RequestParam(defaultValue = "IN_APP") String channel) {
        return R.ok(reportService.saveSubscription(reportTypes, frequency, channel));
    }

    @ApiOperation("取消报表订阅")
    @DeleteMapping("/subscription")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> unsubscribe() {
        return R.ok(reportService.cancelSubscription());
    }

    @ApiOperation("报表详情（不存在则实时生成）")
    @GetMapping("/{type}")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> detail(@PathVariable String type,
                                         @RequestParam(required = false) String date) {
        return R.ok(reportService.detail(type, date));
    }

    @ApiOperation("重新生成报表")
    @PostMapping("/{type}/generate")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "REPORT_GENERATE", target = "DATA_REPORT", description = "重新生成报表")
    public R<Map<String, Object>> generate(@PathVariable String type,
                                           @RequestParam(required = false) String date) {
        return R.ok(reportService.generate(type, date));
    }

    @ApiOperation("分发报表（站内信模拟，失败重试 3 次）")
    @PostMapping("/{type}/dispatch")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "REPORT_DISPATCH", target = "DATA_REPORT", description = "分发报表")
    public R<Map<String, Object>> dispatch(@PathVariable String type,
                                           @RequestParam(required = false) String date) {
        return R.ok(reportService.dispatch(type, date));
    }

    @ApiOperation("导出 CSV（Excel 可直接打开）")
    @GetMapping("/{type}/export")
    @PreAuthorize("hasAuthority('admin:data:export')")
    public ResponseEntity<byte[]> export(@PathVariable String type,
                                         @RequestParam(required = false) String date) {
        String csv = reportService.exportCsv(type, date);
        String filename = "report-" + type.toUpperCase() + "-"
                + (date == null ? "latest" : date) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
