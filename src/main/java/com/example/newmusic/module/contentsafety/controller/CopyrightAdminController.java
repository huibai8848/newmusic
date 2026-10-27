package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.contentsafety.dto.ComplaintHandleReq;
import com.example.newmusic.module.contentsafety.dto.CopyrightSaveReq;
import com.example.newmusic.module.contentsafety.entity.CopyrightComplaint;
import com.example.newmusic.module.contentsafety.entity.CopyrightInfo;
import com.example.newmusic.module.contentsafety.service.CopyrightService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
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

/**
 * 版权信息、到期预警与侵权投诉处置（FR-27-008）。
 */
@Api(tags = "后台接口-版权与侵权")
@RestController
@RequestMapping("/api/v1/admin")
public class CopyrightAdminController {

    private final CopyrightService copyrightService;

    public CopyrightAdminController(CopyrightService copyrightService) {
        this.copyrightService = copyrightService;
    }

    @ApiOperation("版权列表")
    @GetMapping("/copyrights")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<PageResult<CopyrightInfo>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return R.ok(copyrightService.page(pageNum, pageSize, status, keyword));
    }

    @ApiOperation("新增/编辑版权信息（按 songId 覆盖）")
    @PostMapping("/copyrights")
    @PreAuthorize("hasAuthority('admin:copyright:edit')")
    @AdminOpLog(type = "COPYRIGHT_SAVE", target = "COPYRIGHT", description = "维护版权信息")
    public R<CopyrightInfo> save(@RequestBody @Valid CopyrightSaveReq req) {
        return R.ok(copyrightService.save(req));
    }

    @ApiOperation("编辑版权信息")
    @PutMapping("/copyrights")
    @PreAuthorize("hasAuthority('admin:copyright:edit')")
    @AdminOpLog(type = "COPYRIGHT_SAVE", target = "COPYRIGHT", description = "维护版权信息")
    public R<CopyrightInfo> update(@RequestBody @Valid CopyrightSaveReq req) {
        return R.ok(copyrightService.save(req));
    }

    @ApiOperation("版权详情")
    @GetMapping("/copyrights/{id}")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<CopyrightInfo> detail(@PathVariable Long id) {
        return R.ok(copyrightService.get(id));
    }

    @ApiOperation("即将到期（默认 30 天）")
    @GetMapping("/copyrights/expiring")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<List<Map<String, Object>>> expiring(
            @RequestParam(defaultValue = "30") int days) {
        return R.ok(copyrightService.expiring(days));
    }

    @ApiOperation("版权统计")
    @GetMapping("/copyrights/stats")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<Map<String, Object>> stats() {
        return R.ok(copyrightService.stats());
    }

    @ApiOperation(value = "手动执行到期巡检", notes = "发提醒 + 到期自动下架")
    @PostMapping("/copyrights/check")
    @PreAuthorize("hasAuthority('admin:copyright:edit')")
    @AdminOpLog(type = "COPYRIGHT_CHECK", target = "COPYRIGHT", description = "版权到期巡检")
    public R<Map<String, Object>> check() {
        int offline = copyrightService.checkExpiry();
        return R.ok(java.util.Collections.singletonMap("offline", offline));
    }

    @ApiOperation("侵权投诉列表")
    @GetMapping("/complaints")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<PageResult<CopyrightComplaint>> complaints(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status) {
        return R.ok(copyrightService.complaints(pageNum, pageSize, status));
    }

    @ApiOperation("侵权投诉详情（联系方式密文不回传）")
    @GetMapping("/complaints/{id}")
    @PreAuthorize("hasAuthority('admin:copyright:view')")
    public R<CopyrightComplaint> complaint(@PathVariable Long id) {
        return R.ok(copyrightService.complaint(id));
    }

    @ApiOperation("处理侵权投诉：TAKE_DOWN / REJECT / NEED_MORE / COUNTER_NOTICE")
    @PostMapping("/complaints/{id}/handle")
    @PreAuthorize("hasAuthority('admin:copyright:edit')")
    @AdminOpLog(type = "COPYRIGHT_COMPLAINT_HANDLE", target = "COMPLAINT", description = "处理侵权投诉")
    public R<CopyrightComplaint> handle(@PathVariable Long id,
                                        @RequestBody @Valid ComplaintHandleReq req) {
        return R.ok(copyrightService.handleComplaint(id, req));
    }
}
