package com.example.newmusic.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.SysConfigUpdateReq;
import com.example.newmusic.module.admin.entity.SysConfig;
import com.example.newmusic.module.admin.mapper.SysConfigMapper;
import com.example.newmusic.module.admin.service.SysConfigService;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import com.example.newmusic.module.ai.dto.AiAnnotateReq;
import com.example.newmusic.module.ai.dto.AiBudgetUpdateReq;
import com.example.newmusic.module.ai.dto.AiRoutingUpdateReq;
import com.example.newmusic.module.ai.entity.AiCallLog;
import com.example.newmusic.module.ai.job.AiAnnotationJob;
import com.example.newmusic.module.ai.mapper.AiCallLogMapper;
import com.example.newmusic.module.ai.service.AiCostService;
import com.example.newmusic.module.ai.service.LyricTranslationService;
import com.example.newmusic.module.ai.service.SongEmotionService;
import com.example.newmusic.module.ai.support.AiBudgetGuard;
import com.example.newmusic.module.ai.support.AiConfigService;
import com.example.newmusic.module.ai.support.AiJobProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台 AI 运维：成本、日志、质检、批量任务、预算与路由配置。
 *
 * <p>权限拆成两组：看数据用 {@code admin:ai:view}，动手（标注、改配置）用
 * {@code admin:ai:edit} / {@code admin:sysconfig:edit}。成本和配置能改的人应该少一些，
 * 所以不合并成一个「AI 管理员」权限。
 */
@Api(tags = "后台接口-AI")
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {

    private final AiCostService costService;
    private final AiCallLogMapper callLogMapper;
    private final AiBudgetGuard budgetGuard;
    private final AiConfigService aiConfigService;
    private final AiAnnotationJob annotationJob;
    private final SongEmotionService emotionService;
    private final LyricTranslationService translationService;
    private final AiJobProgress jobProgress;
    private final SysConfigService sysConfigService;
    private final SysConfigMapper sysConfigMapper;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public AdminAiController(AiCostService costService, AiCallLogMapper callLogMapper,
                             AiBudgetGuard budgetGuard, AiConfigService aiConfigService,
                             AiAnnotationJob annotationJob, SongEmotionService emotionService,
                             LyricTranslationService translationService,
                             AiJobProgress jobProgress, SysConfigService sysConfigService,
                             SysConfigMapper sysConfigMapper, AiProperties properties,
                             ObjectMapper objectMapper) {
        this.costService = costService;
        this.callLogMapper = callLogMapper;
        this.budgetGuard = budgetGuard;
        this.aiConfigService = aiConfigService;
        this.annotationJob = annotationJob;
        this.emotionService = emotionService;
        this.translationService = translationService;
        this.jobProgress = jobProgress;
        this.sysConfigService = sysConfigService;
        this.sysConfigMapper = sysConfigMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @ApiOperation(value = "AI 成本统计", notes = "含按场景汇总、每日趋势、当日预算状态与生效配置")
    @GetMapping("/cost-stats")
    @PreAuthorize("hasAuthority('admin:ai:view')")
    public R<Map<String, Object>> costStats(@RequestParam(defaultValue = "7") int days) {
        int span = Math.max(1, Math.min(90, days));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(span - 1L);
        Map<String, Object> view = costService.overview(start, end);
        view.put("daily", costService.dailyStats(start, end));
        view.put("budget", budgetGuard.state());
        view.put("config", aiConfigService.currentView());
        view.put("provider", properties.getProvider());
        view.put("jobsEnabled", properties.isJobsEnabled());
        return R.ok(view);
    }

    @ApiOperation(value = "AI 调用日志", notes = "按时间倒序；可按场景与成功/失败过滤")
    @GetMapping("/call-logs")
    @PreAuthorize("hasAuthority('admin:ai:view')")
    public R<PageResult<AiCallLog>> callLogs(PageQuery query,
                                             @RequestParam(required = false) String scene,
                                             @RequestParam(required = false) Integer success) {
        query.normalize();
        QueryWrapper<AiCallLog> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(scene)) {
            wrapper.eq("scene", scene.trim().toUpperCase());
        }
        if (success != null) {
            wrapper.eq("success", success);
        }
        wrapper.orderByDesc("create_time");
        return R.ok(PageResult.of(callLogMapper.selectPage(query.toPage(), wrapper),
                query.isTruncated()));
    }

    @ApiOperation("低置信度标注队列（转人工复核）")
    @GetMapping("/low-confidence")
    @PreAuthorize("hasAuthority('admin:ai:view')")
    public R<List<Map<String, Object>>> lowConfidence(
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(emotionService.lowConfidence(Math.max(1, Math.min(200, limit))));
    }

    @ApiOperation(value = "手动触发标注", notes = "带 songId 处理单曲（情绪强制重算）；不带则触发一批")
    @PostMapping("/annotate")
    @PreAuthorize("hasAuthority('admin:ai:edit')")
    @AdminOpLog(type = "AI_ANNOTATE", target = "AI_JOB", description = "手动触发 AI 标注")
    public R<Map<String, Object>> annotate(@RequestBody(required = false) AiAnnotateReq req) {
        AiAnnotateReq request = req == null ? new AiAnnotateReq() : req;
        String scene = StringUtils.hasText(request.getScene())
                ? request.getScene().trim().toUpperCase() : "EMOTION_TAG";
        Map<String, Object> result = new LinkedHashMap<>();
        if (request.getSongId() != null) {
            result.put("songId", request.getSongId());
            if ("LYRIC_TRANS".equals(scene)) {
                result.put("translation", translationService.generate(request.getSongId(), null));
            } else {
                result.put("emotion", emotionService.annotate(request.getSongId(), null, true));
            }
            return R.ok(result);
        }
        if ("LYRIC_TRANS".equals(scene)) {
            return R.ok(annotationJob.runTranslationPrewarm(request.getLimit()));
        }
        if (!"EMOTION_TAG".equals(scene)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持手动触发的场景：" + scene);
        }
        return R.ok(annotationJob.runEmotion(request.getLimit()));
    }

    @ApiOperation("批量任务进度")
    @GetMapping("/progress/{jobId}")
    @PreAuthorize("hasAuthority('admin:ai:view')")
    public R<Map<String, Object>> progress(@PathVariable String jobId) {
        return R.ok(jobProgress.get(jobId));
    }

    @ApiOperation(value = "修改 AI 预算", notes = "调高预算会立即解除熔断（清掉暂停标记）")
    @PutMapping("/budget")
    @PreAuthorize("hasAuthority('admin:sysconfig:edit')")
    @AdminOpLog(type = "AI_BUDGET_UPDATE", target = "SYS_CONFIG", description = "修改 AI 预算",
            sensitive = true)
    public R<Void> updateBudget(@RequestBody @Valid AiBudgetUpdateReq req) {
        if (req.getDailyBudgetCent() == null && req.getAlertPercent() == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "dailyBudgetCent 与 alertPercent 至少传一个");
        }
        if (req.getDailyBudgetCent() != null) {
            updateConfig(AiConfigService.KEY_DAILY_BUDGET, String.valueOf(req.getDailyBudgetCent()));
        }
        if (req.getAlertPercent() != null) {
            updateConfig(AiConfigService.KEY_ALERT_PERCENT, String.valueOf(req.getAlertPercent()));
        }
        // 预算是人调的，调完就应该立刻按新预算工作：否则「调高了却还是一直熔断」很难解释
        budgetGuard.clearPaused();
        return R.ok();
    }

    @ApiOperation(value = "修改模型路由", notes = "请求体为 {routing:{SCENE:LEVEL}}；未知场景/档位直接拒绝")
    @PutMapping("/model-routing")
    @PreAuthorize("hasAuthority('admin:sysconfig:edit')")
    @AdminOpLog(type = "AI_ROUTING_UPDATE", target = "SYS_CONFIG", description = "修改 AI 模型路由",
            sensitive = true)
    public R<Void> updateRouting(@RequestBody @Valid AiRoutingUpdateReq req) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : req.getRouting().entrySet()) {
            AiScene scene = AiScene.of(entry.getKey());
            if (scene == null) {
                throw new BizException(ResultCode.PARAM_INVALID, "未知场景：" + entry.getKey());
            }
            AiScene.Level level = AiScene.Level.of(entry.getValue(), null);
            if (level == null) {
                throw new BizException(ResultCode.PARAM_INVALID,
                        "档位只能是 SMALL / MEDIUM / LARGE：" + entry.getValue());
            }
            normalized.put(scene.code(), level.name());
        }
        try {
            updateConfig(AiConfigService.KEY_MODEL_ROUTING,
                    objectMapper.writeValueAsString(normalized));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "路由配置序列化失败", e);
        }
        return R.ok();
    }

    /** 复用系统配置的校验 / 历史 / 回滚能力，不直接改表。 */
    private void updateConfig(String key, String value) {
        SysConfig config = sysConfigMapper.selectOne(new QueryWrapper<SysConfig>()
                .eq("config_key", key).last("LIMIT 1"));
        if (config == null) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "缺少配置项 " + key + "，请先执行 sql/14-ai-seed.sql");
        }
        SysConfigUpdateReq update = new SysConfigUpdateReq();
        update.setConfigValue(value);
        update.setRemark("AI 后台配置");
        sysConfigService.update(config.getId(), update);
        aiConfigService.invalidate();
    }
}
