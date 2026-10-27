package com.example.newmusic.common.web;

import com.example.newmusic.common.result.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器时间（模块 13 FR-22-001 / BR-22-010）。
 *
 * <p>客户端时钟同步需要一条**不依赖鉴权**的轻量接口：NTP 采样要反复发请求，
 * 为了拿时间戳先登录一次既没必要，也会让游客房间的同步失效。
 * 返回值只有一个毫秒时间戳与服务器时区，不含任何用户信息。
 */
@Api(tags = "系统时间")
@RestController
@RequestMapping("/api/v1")
public class TimeController {

    @ApiOperation(value = "服务器当前时间", notes = "毫秒时间戳，用于客户端时钟同步")
    @GetMapping("/time")
    public R<Map<String, Object>> time() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", System.currentTimeMillis());
        data.put("timezone", ZoneId.systemDefault().getId());
        return R.ok(data);
    }
}
