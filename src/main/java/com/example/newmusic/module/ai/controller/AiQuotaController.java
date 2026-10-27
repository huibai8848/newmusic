package com.example.newmusic.module.ai.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ai.config.AiScene;
import com.example.newmusic.module.ai.support.AiQuotaService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** AI 额度查询（登录）。前端用它在按钮旁显示「今日剩余 N 次」。 */
@Api(tags = "AI-额度")
@RestController
@RequestMapping("/api/v1/ai")
public class AiQuotaController {

    private final AiQuotaService quotaService;

    public AiQuotaController(AiQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @ApiOperation(value = "我的 AI 额度", notes = "不传 scene 返回所有有限额的场景")
    @GetMapping("/quota")
    public R<List<Map<String, Object>>> quota(@RequestParam(required = false) String scene) {
        Long userId = UserContext.requireUserId();
        if (!StringUtils.hasText(scene)) {
            return R.ok(quotaService.quotaAll(userId));
        }
        AiScene parsed = AiScene.of(scene);
        if (parsed == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "未知 AI 场景：" + scene);
        }
        return R.ok(Collections.singletonList(quotaService.quota(userId, parsed)));
    }
}
