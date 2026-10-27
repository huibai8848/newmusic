package com.example.newmusic.module.play.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.play.dto.EndPlayReq;
import com.example.newmusic.module.play.dto.PlayProgressVO;
import com.example.newmusic.module.play.dto.PlayRecordVO;
import com.example.newmusic.module.play.dto.ProgressReportReq;
import com.example.newmusic.module.play.dto.RecordPlayReq;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.service.PlayHistoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 播放历史上报（FR-16-004）。
 *
 * <p>record/end 对游客开放：游客历史按 deviceId 落库（BR-16-032）；
 * progress/history 是个人数据，必须登录。
 */
@Api(tags = "播放-历史与进度")
@RestController
@RequestMapping("/api/v1/play")
public class PlayHistoryController {

    private final PlayHistoryService playHistoryService;

    public PlayHistoryController(PlayHistoryService playHistoryService) {
        this.playHistoryService = playHistoryService;
    }

    @ApiOperation(value = "播放开始上报",
            notes = "公开接口。5 秒内同一主体同一首歌重复上报返回 duplicated=true 与已有 playId。")
    @PostMapping("/record")
    @RateLimit(limit = 300, windowSeconds = 60, type = "play")
    public R<PlayRecordVO> record(@Valid @RequestBody RecordPlayReq req, HttpServletRequest request) {
        return R.ok(playHistoryService.record(req, UserContext.getUserId(), IpUtil.getClientIp(request)));
    }

    @ApiOperation(value = "播放进度上报", notes = "登录用户；建议每 30 秒一次，切歌/离开页面时补一次。")
    @PostMapping("/progress")
    @RateLimit(limit = 300, windowSeconds = 60, type = "play")
    public R<Void> progress(@Valid @RequestBody ProgressReportReq req, HttpServletRequest request) {
        playHistoryService.progress(req, UserContext.requireUserId(), IpUtil.getClientIp(request));
        return R.ok();
    }

    @ApiOperation(value = "获取播放进度",
            notes = "登录用户。跨设备续播用：返回最近一次进度与来源设备，不自动跳转。"
                    + "前端在同一设备上应优先用本地进度（BR-21-068）。")
    @GetMapping("/progress")
    public R<PlayProgressVO> progress(@RequestParam Long songId,
                                      @RequestParam(required = false) String deviceId) {
        return R.ok(playHistoryService.playProgress(UserContext.requireUserId(), songId, deviceId));
    }

    @ApiOperation(value = "播放结束上报", notes = "公开接口；≥30 秒或 ≥60% 计为有效播放。")
    @PostMapping("/end")
    @RateLimit(limit = 300, windowSeconds = 60, type = "play")
    public R<Void> end(@Valid @RequestBody EndPlayReq req) {
        playHistoryService.end(req, UserContext.getUserId());
        return R.ok();
    }

    @ApiOperation(value = "播放历史", notes = "每首歌只保留最近一次，按最近播放时间倒序。")
    @GetMapping("/history")
    public R<PageResult<PlayHistory>> history(@RequestParam(defaultValue = "1") int pageNum,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(playHistoryService.history(UserContext.requireUserId(), pageNum, pageSize));
    }

    @ApiOperation("删除一条播放历史")
    @DeleteMapping("/history/{id}")
    public R<Void> delete(@PathVariable Long id) {
        playHistoryService.delete(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("清空播放历史")
    @DeleteMapping("/history")
    public R<Void> clear() {
        playHistoryService.clear(UserContext.requireUserId());
        return R.ok();
    }
}
