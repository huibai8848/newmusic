package com.example.newmusic.module.ai.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.ai.dto.LyricFeedbackReq;
import com.example.newmusic.module.ai.dto.LyricTranslationVO;
import com.example.newmusic.module.ai.service.LyricTranslationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 歌词翻译（FR-23-002）。
 *
 * <p>GET 公开、POST 登录：读已有译文不花钱，生成译文要花预算并占用用户额度。
 * 用户级额度在网关的 {@code AiQuotaService} 里（每小时/每天），这里的 {@code @RateLimit}
 * 是 IP 级防刷——两层解决的不是同一个问题。
 */
@Api(tags = "AI-歌词翻译")
@RestController
@RequestMapping("/api/v1/lyric")
public class LyricAiController {

    private final LyricTranslationService translationService;

    public LyricAiController(LyricTranslationService translationService) {
        this.translationService = translationService;
    }

    @ApiOperation(value = "获取歌词翻译", notes = "未生成时 generated=false；lang 目前只支持 zh")
    @GetMapping("/{songId}/translation")
    @RateLimit(limit = 120, windowSeconds = 60, type = "ai")
    public R<LyricTranslationVO> get(@PathVariable Long songId,
                                     @RequestParam(required = false) String lang) {
        checkLang(lang);
        return R.ok(translationService.get(songId));
    }

    @ApiOperation(value = "生成歌词翻译", notes = "需要登录；已存在译文时幂等返回，不产生 AI 成本")
    @PostMapping("/{songId}/translation")
    @RateLimit(limit = 10, windowSeconds = 60, type = "ai")
    public R<LyricTranslationVO> generate(@PathVariable Long songId) {
        Long userId = UserContext.requireUserId();
        return R.ok(translationService.generate(songId, userId));
    }

    @ApiOperation("翻译质量反馈")
    @PostMapping("/{songId}/translation/feedback")
    @RateLimit(limit = 5, windowSeconds = 60, type = "ai")
    public R<Long> feedback(@PathVariable Long songId,
                            @RequestBody @Valid LyricFeedbackReq req) {
        Long userId = UserContext.requireUserId();
        return R.ok(translationService.feedback(songId, userId, req.getIssue()));
    }

    private void checkLang(String lang) {
        if (!StringUtils.hasText(lang)) {
            return;
        }
        String normalized = lang.trim().toLowerCase();
        if (!"zh".equals(normalized) && !"zh-cn".equals(normalized) && !"zh_cn".equals(normalized)) {
            throw new BizException(ResultCode.PARAM_INVALID, "目前只支持翻译为中文");
        }
    }
}
