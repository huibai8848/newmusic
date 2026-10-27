package com.example.newmusic.module.play.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.play.dto.PlaySettingReq;
import com.example.newmusic.module.play.entity.UserPlaySetting;
import com.example.newmusic.module.play.service.PlaySettingService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 播放设置（模块 12 FR-21-008）。
 *
 * <p>只对登录用户开放：游客的偏好存 localStorage，不落服务端（BR-21-078）。
 */
@Api(tags = "播放-设置")
@RestController
@RequestMapping("/api/v1/play")
public class PlaySettingController {

    private final PlaySettingService playSettingService;

    public PlaySettingController(PlaySettingService playSettingService) {
        this.playSettingService = playSettingService;
    }

    @ApiOperation(value = "获取播放设置",
            notes = "配置行不存在时返回默认值（默认音质 high、自动降质开、倍速 1.0），首次更新才落库。")
    @GetMapping("/settings")
    public R<UserPlaySetting> get() {
        return R.ok(playSettingService.get(UserContext.requireUserId()));
    }

    @ApiOperation(value = "更新播放设置",
            notes = "字段可空，只改传入的项；非法取值返回 30061。音量不在同步范围内（BR-21-076）。")
    @PutMapping("/settings")
    @RateLimit(limit = 60, windowSeconds = 60, type = "play")
    public R<UserPlaySetting> update(@Valid @RequestBody PlaySettingReq req) {
        return R.ok(playSettingService.update(UserContext.requireUserId(), req));
    }
}
