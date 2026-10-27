package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.service.AdminDashboardService;
import com.example.newmusic.module.admin.vo.DashboardVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "后台接口-运营看板")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @ApiOperation(value = "看板总览", notes = "用户/歌曲/审核/举报/房间/播放的真实聚合；含近 7 天趋势")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:dashboard:view')")
    public R<DashboardVO> overview() {
        return R.ok(dashboardService.overview());
    }
}
