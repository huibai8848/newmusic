package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.dataplatform.dto.FunnelReq;
import com.example.newmusic.module.dataplatform.service.FunnelService;
import com.example.newmusic.module.dataplatform.service.RetentionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/** 漏斗与留存分析（FR-26-006 / FR-26-007）。 */
@Api(tags = "后台接口-分析")
@RestController
@RequestMapping("/api/v1/admin/analysis")
public class AdminAnalysisController {

    private final FunnelService funnelService;
    private final RetentionService retentionService;

    public AdminAnalysisController(FunnelService funnelService, RetentionService retentionService) {
        this.funnelService = funnelService;
        this.retentionService = retentionService;
    }

    @ApiOperation("7 个预置漏斗")
    @GetMapping("/funnel/presets")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<Map<String, Object>>> presets() {
        return R.ok(funnelService.presets());
    }

    @ApiOperation("漏斗查询（支持任意步骤组合与分维度）")
    @PostMapping("/funnel")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> funnel(@Valid @RequestBody FunnelReq req) {
        return R.ok(funnelService.query(req));
    }

    @ApiOperation("留存分析（新增/活跃/播放，支持分群与同期群）")
    @GetMapping("/retention")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> retention(@RequestParam(defaultValue = "new") String type,
                                            @RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end,
                                            @RequestParam(required = false) String group) {
        return R.ok(retentionService.analysis(type, start, end, group));
    }
}
