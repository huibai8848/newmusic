package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.AdminLogQueryReq;
import com.example.newmusic.module.admin.dto.LoginLogQueryReq;
import com.example.newmusic.module.admin.entity.LoginLog;
import com.example.newmusic.module.admin.entity.OperationLog;
import com.example.newmusic.module.admin.service.AdminLogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;

/**
 * 日志查询。全量可见（admin:log:view）与仅本人（admin:log:view:self）是两条权限；
 * 导出只授予超管（BR-18-133），并额外写一条敏感操作日志。
 */
@Api(tags = "后台接口-日志")
@RestController
@RequestMapping("/api/v1/admin/logs")
public class AdminLogController {

    private final AdminLogService logService;

    public AdminLogController(AdminLogService logService) {
        this.logService = logService;
    }

    @ApiOperation(value = "操作日志", notes = "无 admin:log:view 时只能看到自己的记录")
    @GetMapping("/operations")
    @PreAuthorize("hasAnyAuthority('admin:log:view','admin:log:view:self')")
    public R<PageResult<OperationLog>> operations(AdminLogQueryReq query) {
        return R.ok(logService.operations(query, !UserContext.hasPermission("admin:log:view")));
    }

    @ApiOperation(value = "登录日志", notes = "无 admin:log:view 时只能看到自己的后台登录记录")
    @GetMapping("/logins")
    @PreAuthorize("hasAnyAuthority('admin:log:view','admin:log:view:self')")
    public R<PageResult<LoginLog>> logins(LoginLogQueryReq query) {
        return R.ok(logService.loginLogs(query, !UserContext.hasPermission("admin:log:view")));
    }

    @ApiOperation(value = "导出操作日志 CSV", notes = "仅超管；最多 20000 行")
    @GetMapping("/operations/export")
    @PreAuthorize("hasAuthority('admin:log:export')")
    @AdminOpLog(type = "LOG_EXPORT", target = "LOG", description = "导出操作日志", sensitive = true)
    public void export(AdminLogQueryReq query, HttpServletResponse response) throws IOException {
        String filename = "operation-logs-" + LocalDate.now() + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, "UTF-8")
                        .replace("+", "%20"));
        logService.exportOperations(query, !UserContext.hasPermission("admin:log:view"),
                response.getOutputStream());
    }
}
