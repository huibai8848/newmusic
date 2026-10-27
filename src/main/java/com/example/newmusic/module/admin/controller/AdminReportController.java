package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.ReportHandleReq;
import com.example.newmusic.module.admin.dto.ReportQueryReq;
import com.example.newmusic.module.admin.entity.Report;
import com.example.newmusic.module.admin.service.ReportService;
import com.example.newmusic.module.contentsafety.service.ReportSafetyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

@Api(tags = "后台接口-举报处理")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final ReportService reportService;
    private final ReportSafetyService reportSafetyService;

    public AdminReportController(ReportService reportService,
                                 ReportSafetyService reportSafetyService) {
        this.reportService = reportService;
        this.reportSafetyService = reportSafetyService;
    }

    @ApiOperation("举报列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:report:list')")
    public R<PageResult<Report>> page(ReportQueryReq query) {
        return R.ok(reportService.page(query));
    }

    @ApiOperation("举报详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:report:list')")
    public R<Report> detail(@PathVariable Long id) {
        return R.ok(reportService.detail(id));
    }

    @ApiOperation(value = "处理举报", notes = "成立/不成立/待补充；可联动下架歌曲、封禁用户、关闭房间")
    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('admin:report:handle')")
    @AdminOpLog(type = "REPORT_HANDLE", target = "REPORT", description = "处理举报")
    public R<Report> handle(@PathVariable Long id, @RequestBody @Valid ReportHandleReq req) {
        return R.ok(reportService.handle(id, req));
    }

    @ApiOperation("举报统计（按目标/原因/自动隐藏/监管）")
    @GetMapping("/stats")
    @PreAuthorize("hasAnyAuthority('admin:report:list','admin:audit:stats')")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "30") int days) {
        return R.ok(reportSafetyService.stats(days));
    }

    @ApiOperation("举报 SLA 统计")
    @GetMapping("/sla-stats")
    @PreAuthorize("hasAnyAuthority('admin:report:list','admin:audit:stats')")
    public R<Map<String, Object>> slaStats() {
        return R.ok(reportSafetyService.slaStats());
    }

    @ApiOperation("监管转办（优先级最高 + 2 小时 SLA）")
    @PostMapping("/{id}/regulator")
    @PreAuthorize("hasAuthority('admin:report:handle')")
    @AdminOpLog(type = "REPORT_REGULATOR", target = "REPORT", description = "监管转办举报")
    public R<Report> regulator(@PathVariable Long id) {
        return R.ok(reportSafetyService.markRegulator(id));
    }
}
