package com.example.newmusic.module.play.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.play.dto.PlayQualitiesVO;
import com.example.newmusic.module.play.dto.PlayUrlVO;
import com.example.newmusic.module.play.service.PlayQualityService;
import com.example.newmusic.module.play.service.PlayUrlService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "播放-地址签发")
@RestController
@RequestMapping("/api/v1/player")
public class PlayerController {

    private final PlayUrlService playUrlService;
    private final PlayQualityService playQualityService;

    public PlayerController(PlayUrlService playUrlService, PlayQualityService playQualityService) {
        this.playUrlService = playUrlService;
        this.playQualityService = playQualityService;
    }

    @ApiOperation(value = "获取播放地址",
            notes = "公开接口（游客可调）。做存在/上架/审核/版权校验后签发带 expires 与 sign 的地址，有效期 2 小时；"
                    + "请求音质无对应音源时自动降级，响应里的 downgraded 标识是否降级。")
    @GetMapping("/url")
    // 地址签发是公开且带签名计算的接口，是天然的刷取目标（PRD：300 次/分钟/IP）
    @RateLimit(limit = 300, windowSeconds = 60, type = "player")
    public R<PlayUrlVO> url(@ApiParam(value = "歌曲ID", required = true) @RequestParam Long songId,
                            @ApiParam("音质 standard/high/lossless，默认 standard")
                            @RequestParam(required = false) String quality) {
        // 未登录时 getUserId() 为 null，签名里用游客占位（BR-16-010 的 userId 段）
        return R.ok(playUrlService.issue(songId, quality, UserContext.getUserId()));
    }

    @ApiOperation(value = "获取可用音质列表",
            notes = "公开接口。不传 songId 时只返回权限维度的可用性；传了则同时判断该歌曲是否有对应音源。"
                    + "无损仅 VIP 可用，游客仅标准音质。")
    @GetMapping("/qualities")
    @RateLimit(limit = 300, windowSeconds = 60, type = "player")
    public R<PlayQualitiesVO> qualities(@ApiParam("歌曲ID") @RequestParam(required = false) Long songId) {
        return R.ok(playQualityService.list(songId, UserContext.getUserId()));
    }
}
