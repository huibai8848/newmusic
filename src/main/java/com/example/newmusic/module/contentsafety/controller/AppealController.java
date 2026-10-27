package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.contentsafety.dto.AppealCreateReq;
import com.example.newmusic.module.contentsafety.dto.AppealHandleReq;
import com.example.newmusic.module.contentsafety.dto.AppealQueryReq;
import com.example.newmusic.module.contentsafety.entity.ContentAppeal;
import com.example.newmusic.module.contentsafety.service.AppealService;
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

/**
 * 处置申诉（FR-27-005）。
 *
 * <p>C 端只能看到自己的申诉；复核必须换人（原审核员不能复核），
 * 这些约束在 Service 层，而不是只靠前端隐藏按钮。
 */
@Api(tags = "申诉")
@RestController
@RequestMapping("/api/v1")
public class AppealController {

    private final AppealService appealService;

    public AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @ApiOperation("提交申诉（7 天内，每处置 1 次，每日 3 次）")
    @PostMapping("/appeals")
    public R<ContentAppeal> create(@RequestBody @Valid AppealCreateReq req) {
        return R.ok(appealService.create(req));
    }

    @ApiOperation("我的申诉")
    @GetMapping("/appeals/my")
    public R<PageResult<ContentAppeal>> my(@RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(appealService.my(pageNum, pageSize));
    }

    @ApiOperation("申诉详情")
    @GetMapping("/appeals/{id}")
    public R<ContentAppeal> myDetail(@PathVariable Long id) {
        return R.ok(appealService.myDetail(id));
    }

    @ApiOperation("后台申诉列表")
    @GetMapping("/admin/appeals")
    @PreAuthorize("hasAuthority('admin:appeal:view')")
    public R<PageResult<ContentAppeal>> page(AppealQueryReq query) {
        return R.ok(appealService.page(query));
    }

    @ApiOperation("后台申诉统计")
    @GetMapping("/admin/appeals/stats")
    @PreAuthorize("hasAuthority('admin:appeal:view')")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "30") int days) {
        return R.ok(appealService.stats(days));
    }

    @ApiOperation("后台申诉详情")
    @GetMapping("/admin/appeals/{id}")
    @PreAuthorize("hasAuthority('admin:appeal:view')")
    public R<ContentAppeal> adminDetail(@PathVariable Long id) {
        return R.ok(appealService.detail(id));
    }

    @ApiOperation("处理申诉")
    @PostMapping("/admin/appeals/{id}/handle")
    @PreAuthorize("hasAuthority('admin:appeal:edit')")
    @AdminOpLog(type = "APPEAL_HANDLE", target = "APPEAL", description = "处理申诉")
    public R<ContentAppeal> handle(@PathVariable Long id,
                                   @RequestBody @Valid AppealHandleReq req) {
        return R.ok(appealService.handle(id, req));
    }
}
