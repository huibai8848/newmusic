package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.AuditHandleReq;
import com.example.newmusic.module.admin.dto.AuditTaskQueryReq;
import com.example.newmusic.module.admin.entity.AuditRecord;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.service.AuditService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "后台接口-审核工作台")
@RestController
@RequestMapping("/api/v1/admin/audits")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @ApiOperation(value = "审核任务列表", notes = "默认按状态/优先级/提交时间排序；onlyMine 只看自己领取的")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:audit:list')")
    public R<PageResult<AuditTask>> page(AuditTaskQueryReq query) {
        return R.ok(auditService.page(query));
    }

    @ApiOperation("审核任务详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:audit:list')")
    public R<AuditTask> detail(@PathVariable Long id) {
        return R.ok(auditService.detail(id));
    }

    @ApiOperation("审核记录")
    @GetMapping("/{id}/records")
    @PreAuthorize("hasAuthority('admin:audit:list')")
    public R<List<AuditRecord>> records(@PathVariable Long id) {
        return R.ok(auditService.records(id));
    }

    @ApiOperation(value = "领取任务", notes = "10 分钟内有效；超时自动放回待领池")
    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('admin:audit:handle')")
    public R<AuditTask> claim(@PathVariable Long id) {
        return R.ok(auditService.claim(id));
    }

    @ApiOperation("释放任务")
    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('admin:audit:handle')")
    public R<Void> release(@PathVariable Long id) {
        auditService.release(id);
        return R.ok();
    }

    @ApiOperation(value = "处理任务", notes = "拒绝必须填写不少于 5 字的理由；歌曲类任务会同步审核状态")
    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('admin:audit:handle')")
    @AdminOpLog(type = "AUDIT_HANDLE", target = "AUDIT_TASK", description = "处理审核任务")
    public R<AuditTask> handle(@PathVariable Long id, @RequestBody @Valid AuditHandleReq req) {
        return R.ok(auditService.handle(id, req));
    }
}
