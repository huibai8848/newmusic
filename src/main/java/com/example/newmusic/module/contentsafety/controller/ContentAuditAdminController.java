package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.entity.AuditRecord;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.contentsafety.dto.AuditBatchDecisionReq;
import com.example.newmusic.module.contentsafety.dto.AuditDecisionReq;
import com.example.newmusic.module.contentsafety.dto.AuditQueueQuery;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.contentsafety.dto.TextCheckReq;
import com.example.newmusic.module.contentsafety.service.AuditWorkbenchService;
import com.example.newmusic.module.contentsafety.service.ContentAuditService;
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
import java.util.List;
import java.util.Map;

/**
 * 内容审核：机审文本检测 + 人工审核工作台（FR-27-003/004）。
 *
 * <p>人工审核的每条结论都写 t_audit_record（操作轨迹）与 t_content_audit_record
 * （内容决策），前者用于工作台时间线，后者用于统计与申诉定位。
 */
@Api(tags = "后台接口-内容审核")
@RestController
@RequestMapping("/api/v1")
public class ContentAuditAdminController {

    private final ContentAuditService auditService;
    private final AuditWorkbenchService workbenchService;

    public ContentAuditAdminController(ContentAuditService auditService,
                                       AuditWorkbenchService workbenchService) {
        this.auditService = auditService;
        this.workbenchService = workbenchService;
    }

    @ApiOperation(value = "文本检测", notes = "后台调试用；命中词只返回掩码")
    // 必须挂在 /api/v1/admin/** 下：后台独立过滤链只接管这个前缀，
    // 放在 /api/v1/audit/** 会落进 C 端链，后台 Cookie/Bearer 都不会被解析，必然 401。
    @PostMapping("/admin/audit/text-check")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<AuditResult> textCheck(@RequestBody @Valid TextCheckReq req) {
        AuditRequest request = AuditRequest.of(req.getContentType(), req.getContentId(),
                req.getText(), req.getUserId(), req.getScene());
        return R.ok(auditService.audit(request));
    }

    @ApiOperation("审核队列")
    @GetMapping("/admin/audit/queue")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<PageResult<AuditTask>> queue(AuditQueueQuery query) {
        return R.ok(workbenchService.queue(query));
    }

    @ApiOperation("审核队列统计")
    @GetMapping("/admin/audit/queue-stats")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<Map<String, Object>> queueStats() {
        return R.ok(workbenchService.queueStats());
    }

    @ApiOperation("任务详情")
    @GetMapping("/admin/audit/{id}")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<AuditTask> detail(@PathVariable Long id) {
        return R.ok(workbenchService.detail(id));
    }

    @ApiOperation("任务操作轨迹")
    @GetMapping("/admin/audit/{id}/records")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<List<AuditRecord>> trail(@PathVariable Long id) {
        return R.ok(workbenchService.records(id));
    }

    @ApiOperation("领取任务（10 分钟锁）")
    @PostMapping("/admin/audit/{id}/claim")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    @AdminOpLog(type = "AUDIT_CLAIM", target = "AUDIT_TASK", description = "领取审核任务")
    public R<AuditTask> claim(@PathVariable Long id) {
        return R.ok(workbenchService.claim(id));
    }

    @ApiOperation("释放领取")
    @PostMapping("/admin/audit/{id}/release")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    public R<Void> release(@PathVariable Long id) {
        workbenchService.release(id);
        return R.ok();
    }

    @ApiOperation("提交审核结论")
    @PostMapping("/admin/audit/{id}/decision")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    @AdminOpLog(type = "AUDIT_DECISION", target = "AUDIT_TASK", description = "提交审核结论")
    public R<AuditTask> decide(@PathVariable Long id,
                               @RequestBody @Valid AuditDecisionReq req) {
        return R.ok(workbenchService.decide(id, req));
    }

    @ApiOperation("批量审核（最多 100 条，需 confirmed=true）")
    @PostMapping("/admin/audit/batch-decision")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    @AdminOpLog(type = "AUDIT_BATCH_DECISION", target = "AUDIT_TASK", description = "批量审核")
    public R<Map<String, Object>> batchDecide(
            @RequestBody @Valid AuditBatchDecisionReq req) {
        return R.ok(workbenchService.batchDecide(req));
    }

    @ApiOperation("审核记录查询")
    @GetMapping("/admin/audit/records")
    @PreAuthorize("hasAuthority('admin:audit:view')")
    public R<List<AuditRecord>> records(@RequestParam Long taskId) {
        return R.ok(workbenchService.records(taskId));
    }

    @ApiOperation("内容安全统计")
    @GetMapping("/admin/audit/stats")
    @PreAuthorize("hasAnyAuthority('admin:audit:view','admin:audit:stats')")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days) {
        return R.ok(workbenchService.stats(days));
    }

    @ApiOperation("审核时效统计")
    @GetMapping("/admin/audit/sla-stats")
    @PreAuthorize("hasAnyAuthority('admin:audit:view','admin:audit:stats')")
    public R<Map<String, Object>> slaStats() {
        return R.ok(workbenchService.slaStats());
    }

    @ApiOperation("审核日报")
    @GetMapping("/admin/audit/daily-report")
    @PreAuthorize("hasAnyAuthority('admin:audit:view','admin:audit:stats')")
    public R<Map<String, Object>> dailyReport() {
        return R.ok(workbenchService.dailyReport());
    }

}
