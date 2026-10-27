package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.contentsafety.dto.MinorModeReq;
import com.example.newmusic.module.contentsafety.entity.MinorProtection;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * C 端青少年模式（FR-27-009）。
 *
 * <p>{@code PUT} 同时承担开启与关闭：开启需要设置/校验密码，关闭必须校验密码，
 * 未成年人账号关闭会被 72023 拒绝。
 */
@Api(tags = "青少年模式")
@RestController
@RequestMapping("/api/v1/users/me")
public class MinorProtectionController {

    private final MinorProtectionService minorService;

    public MinorProtectionController(MinorProtectionService minorService) {
        this.minorService = minorService;
    }

    @ApiOperation("青少年模式状态")
    @GetMapping("/minor-mode")
    public R<Map<String, Object>> status() {
        return R.ok(minorService.status(UserContext.requireUserId()));
    }

    @ApiOperation("开启/关闭青少年模式")
    @PutMapping("/minor-mode")
    public R<Map<String, Object>> toggle(@RequestBody @Valid MinorModeReq req) {
        return R.ok(minorService.toggleMode(UserContext.requireUserId(), req));
    }

    @ApiOperation("使用心跳（1 分钟计 1 次，超时/超时长返回 72021/72022）")
    @PostMapping("/minor-mode/heartbeat")
    public R<Map<String, Object>> heartbeat() {
        return R.ok(minorService.heartbeat(UserContext.requireUserId()));
    }

    @ApiOperation("我监护的未成年人")
    @GetMapping("/wards")
    public R<List<Map<String, Object>>> wards() {
        return R.ok(minorService.wards(UserContext.requireUserId()));
    }

    @ApiOperation("绑定我为监护人")
    @PostMapping("/wards/{minorUserId}")
    public R<MinorProtection> bind(@PathVariable Long minorUserId) {
        return R.ok(minorService.bindGuardian(minorUserId, UserContext.requireUserId()));
    }
}
