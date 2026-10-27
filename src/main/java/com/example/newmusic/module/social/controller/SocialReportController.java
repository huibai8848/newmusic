package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.ReportCreateReq;
import com.example.newmusic.module.social.service.SocialReportService;
import com.example.newmusic.module.social.vo.ReportReasonVO;
import com.example.newmusic.module.social.vo.ReportVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "社交-举报")
@RestController
@RequestMapping("/api/v1/reports")
public class SocialReportController {

    private final SocialReportService reportService;

    public SocialReportController(SocialReportService reportService) {
        this.reportService = reportService;
    }

    @ApiOperation("举报理由字典")
    @GetMapping("/reasons")
    public R<List<ReportReasonVO>> reasons() {
        return R.ok(reportService.reasons());
    }

    @ApiOperation(value = "提交举报", notes = "同一目标+理由 24 小时内重复举报会被拒绝；相同待处理举报自动合并。")
    @PostMapping
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_report")
    public R<ReportVO> create(@Valid @RequestBody ReportCreateReq req) {
        return R.ok(reportService.create(UserContext.requireUserId(), req));
    }

    @ApiOperation("我的举报记录")
    @GetMapping("/my")
    public R<PageResult<ReportVO>> my(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return R.ok(reportService.my(UserContext.requireUserId(), page, size));
    }
}
