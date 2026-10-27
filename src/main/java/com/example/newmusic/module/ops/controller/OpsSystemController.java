package com.example.newmusic.module.ops.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.ops.service.DegradeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端系统状态（FR-28-003 / FR-28-011）。
 *
 * <p>降级时前端需要在**未登录**状态下拿到公告与关闭的功能列表，所以这两个接口是公开的；
 * 只暴露等级、文案与功能开关，不暴露阈值、操作人、内部指标。
 */
@Api(tags = "C端接口-系统状态")
@RestController
@RequestMapping("/api/v1/system")
public class OpsSystemController {

    private final DegradeService degradeService;

    public OpsSystemController(DegradeService degradeService) {
        this.degradeService = degradeService;
    }

    @ApiOperation("降级状态（公开，前端据此隐藏功能/显示公告）")
    @GetMapping("/degrade-status")
    public R<Map<String, Object>> degradeStatus() {
        return R.ok(degradeService.publicStatus());
    }

    @ApiOperation("系统存活探测（公开，极简）")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "UP");
        map.put("degradeLevel", degradeService.currentLevel());
        map.put("timestamp", System.currentTimeMillis());
        return R.ok(map);
    }
}
