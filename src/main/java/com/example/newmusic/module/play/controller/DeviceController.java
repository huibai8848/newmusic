package com.example.newmusic.module.play.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.play.dto.DeviceVO;
import com.example.newmusic.module.play.service.PlayDeviceService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备管理（模块 12 BR-21-071）。
 *
 * <p>设备由播放上报自动登记，这里只提供查看与登出。当前 JWT 不按设备签发，
 * 因此「登出设备」只把该设备标记为已登出并移出列表，不会让其它设备上的
 * accessToken 立即失效——账号级失效仍走改密/后台封禁。
 */
@Api(tags = "播放-设备")
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final PlayDeviceService playDeviceService;

    public DeviceController(PlayDeviceService playDeviceService) {
        this.playDeviceService = playDeviceService;
    }

    @ApiOperation(value = "设备列表", notes = "按最后活跃时间倒序，最多返回 50 台；当前设备会标记 current=true。")
    @GetMapping
    public R<List<DeviceVO>> list(@ApiParam("当前设备的 deviceId，用于标记 current")
                                  @RequestParam(required = false) String deviceId) {
        return R.ok(playDeviceService.list(UserContext.requireUserId(), deviceId));
    }

    @ApiOperation(value = "登出一台设备", notes = "设备不存在或已登出返回 30062。")
    @DeleteMapping("/{deviceId}")
    @RateLimit(limit = 30, windowSeconds = 60, type = "device")
    public R<Void> logout(@PathVariable String deviceId) {
        playDeviceService.logout(UserContext.requireUserId(), deviceId);
        return R.ok();
    }

    @ApiOperation(value = "登出全部设备", notes = "返回本次标记为已登出的设备台数。")
    @DeleteMapping("/all")
    @RateLimit(limit = 10, windowSeconds = 60, type = "device")
    public R<Integer> logoutAll() {
        return R.ok(playDeviceService.logoutAll(UserContext.requireUserId()));
    }
}
