package com.example.newmusic.module.recupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.recupgrade.dto.RecEventRequest;
import com.example.newmusic.module.recupgrade.dto.RecFeedRequest;
import com.example.newmusic.module.recupgrade.service.RecEngine;
import com.example.newmusic.module.recupgrade.service.RecTrackService;
import com.example.newmusic.module.recupgrade.vo.RecFeedVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 推荐流（模块 15）。
 *
 * <p>游客可调用：游客没有画像，走 S0 冷启动 + 热门/编辑/新歌召回，
 * 实验分流主体退化为 {@code deviceId}。埋点同样对游客开放，
 * 否则冷启动流量在实验报告里会凭空消失。
 */
@Api(tags = "推荐-推荐流")
@RestController
@RequestMapping("/api/v1/recommend")
public class RecFeedController {

    private final RecEngine recEngine;
    private final RecTrackService trackService;

    public RecFeedController(RecEngine recEngine, RecTrackService trackService) {
        this.recEngine = recEngine;
        this.trackService = trackService;
    }

    @ApiOperation(value = "推荐流（POST）", notes = "15 源召回 → 融合 → 粗排 → 精排 → 重排；返回 traceId 供埋点。")
    @PostMapping("/feed")
    @RateLimit(limit = 120, windowSeconds = 60, type = "recommend")
    public R<RecFeedVO> feed(@Valid @RequestBody(required = false) RecFeedRequest request) {
        return R.ok(recEngine.feed(UserContext.getUserId(), request));
    }

    @ApiOperation(value = "推荐流（GET，便于快速验证）",
            notes = "参数与 POST 一致；埋点仍建议走 POST。")
    @GetMapping("/feed")
    @RateLimit(limit = 120, windowSeconds = 60, type = "recommend")
    public R<RecFeedVO> feedByGet(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long seedSongId,
            @RequestParam(required = false) Long seedPlaylistId,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String deviceType) {
        RecFeedRequest request = new RecFeedRequest();
        if (scene != null && !scene.trim().isEmpty()) {
            request.setScene(scene);
        }
        if (limit != null) {
            request.setLimit(Math.max(1, Math.min(limit, 100)));
        }
        request.setSeedSongId(seedSongId);
        request.setSeedPlaylistId(seedPlaylistId);
        request.setDeviceId(deviceId);
        request.setDeviceType(deviceType);
        return R.ok(recEngine.feed(UserContext.getUserId(), request));
    }

    @ApiOperation(value = "推荐埋点上报", notes = "IMPRESSION/CLICK/PLAY/VALID_PLAY/COMPLETE/SKIP/LIKE/DISLIKE；非法类型静默丢弃。")
    @PostMapping("/events")
    @RateLimit(limit = 600, windowSeconds = 60, type = "recommend")
    public R<Void> event(@Valid @RequestBody RecEventRequest request) {
        trackService.record(UserContext.getUserId(), request);
        return R.ok();
    }

    @ApiOperation("批量埋点上报（一次最多 50 条）")
    @PostMapping("/events/batch")
    @RateLimit(limit = 120, windowSeconds = 60, type = "recommend")
    public R<Map<String, Integer>> batchEvent(
            @ApiParam("事件列表") @RequestBody List<RecEventRequest> requests) {
        int accepted = 0;
        if (requests != null) {
            int limit = Math.min(requests.size(), 50);
            for (int i = 0; i < limit; i++) {
                RecEventRequest request = requests.get(i);
                if (request != null && request.getTraceId() != null && request.getEventType() != null) {
                    trackService.record(UserContext.getUserId(), request);
                    accepted++;
                }
            }
        }
        return R.ok(java.util.Collections.singletonMap("accepted", accepted));
    }
}
