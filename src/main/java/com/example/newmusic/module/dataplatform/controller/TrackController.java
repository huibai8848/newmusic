package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.dataplatform.dto.TrackBatchReq;
import com.example.newmusic.module.dataplatform.dto.TrackEventReq;
import com.example.newmusic.module.dataplatform.service.TrackIngestService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * C 端埋点采集入口（FR-26-002）。
 *
 * <p>接口匿名额开放：未登录用户也要能上报 page_view / player_* 等匿名事件。
 * JWT 过滤器仍然会执行，带了 token 就把 user_id 填上；不带 token 时按设备归因。
 */
@Api(tags = "埋点采集")
@RestController
@RequestMapping("/api/v1/track")
public class TrackController {

    private final TrackIngestService ingestService;

    public TrackController(TrackIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @ApiOperation("上报单个事件")
    @PostMapping("/events")
    public R<Map<String, Object>> one(@RequestBody TrackEventReq req, HttpServletRequest http) {
        return R.ok(ingestService.ingestOne(req, http));
    }

    @ApiOperation("批量上报（最多 200 条）")
    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody TrackBatchReq req, HttpServletRequest http) {
        return R.ok(ingestService.ingestBatch(req, http));
    }
}
