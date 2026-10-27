package com.example.newmusic.module.recupgrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.recupgrade.entity.RecConfig;
import com.example.newmusic.module.recupgrade.entity.RecLog;
import com.example.newmusic.module.recupgrade.experiment.ExperimentService;
import com.example.newmusic.module.recupgrade.feature.LocalFeatureService;
import com.example.newmusic.module.recupgrade.job.RecJob;
import com.example.newmusic.module.recupgrade.mapper.RecLogMapper;
import com.example.newmusic.module.recupgrade.rank.FineRanker;
import com.example.newmusic.module.recupgrade.recall.RecallEngine;
import com.example.newmusic.module.recupgrade.service.NewSongService;
import com.example.newmusic.module.recupgrade.service.OfflineEvaluateService;
import com.example.newmusic.module.recupgrade.service.RecMetricsService;
import com.example.newmusic.module.recupgrade.service.RecTrackService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台-推荐配置、链路排查与监控（FR-24-011 / P1）。
 *
 * <p>排查入口以 traceId 为中心：召回各源状态 → 融合/排序分数 → 重排每一步 →
 * 最终列表，全部能按一次请求还原。这正是 AC-24-011「推荐可解释」的落地方式。
 */
@Api(tags = "后台接口-推荐配置与监控")
@RestController
@RequestMapping("/api/v1/admin/rec")
public class AdminRecController {

    private final RecConfigService configService;
    private final RecallEngine recallEngine;
    private final FineRanker fineRanker;
    private final RecMetricsService metricsService;
    private final NewSongService newSongService;
    private final LocalFeatureService featureService;
    private final RecTrackService trackService;
    private final OfflineEvaluateService evaluateService;
    private final ExperimentService experimentService;
    private final RecJob recJob;
    private final RecLogMapper logMapper;

    public AdminRecController(RecConfigService configService, RecallEngine recallEngine,
                              FineRanker fineRanker, RecMetricsService metricsService,
                              NewSongService newSongService, LocalFeatureService featureService,
                              RecTrackService trackService, OfflineEvaluateService evaluateService,
                              ExperimentService experimentService, RecJob recJob,
                              RecLogMapper logMapper) {
        this.configService = configService;
        this.recallEngine = recallEngine;
        this.fineRanker = fineRanker;
        this.metricsService = metricsService;
        this.newSongService = newSongService;
        this.featureService = featureService;
        this.trackService = trackService;
        this.evaluateService = evaluateService;
        this.experimentService = experimentService;
        this.recJob = recJob;
        this.logMapper = logMapper;
    }

    // ------------------------------------------------------------------ 配置

    @ApiOperation("当前生效配置 + 全部历史版本")
    @GetMapping("/config/{scene}/{type}")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> config(@PathVariable String scene, @PathVariable String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", configService.activeRow(scene, type));
        result.put("history", configService.history(scene, type));
        return R.ok(result);
    }

    @ApiOperation(value = "更新配置", notes = "写入新版本并立即失效缓存，下一次请求生效。")
    @PutMapping("/config/{scene}/{type}")
    @PreAuthorize("hasAuthority('admin:rec:edit')")
    @AdminOpLog(type = "REC_CONFIG_UPDATE", target = "REC_CONFIG", description = "更新推荐配置", sensitive = true)
    public R<Map<String, Object>> updateConfig(@PathVariable String scene, @PathVariable String type,
                                               @RequestBody Map<String, String> body) {
        int version = configService.update(scene.toUpperCase(), type.toUpperCase(),
                body == null ? null : body.get("configJson"), UserContext.getUserId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        return R.ok(result);
    }

    @ApiOperation("回滚到指定版本")
    @PostMapping("/config/{scene}/{type}/rollback")
    @PreAuthorize("hasAuthority('admin:rec:edit')")
    @AdminOpLog(type = "REC_CONFIG_ROLLBACK", target = "REC_CONFIG", description = "回滚推荐配置", sensitive = true)
    public R<RecConfig> rollback(@PathVariable String scene, @PathVariable String type,
                                 @RequestParam int version) {
        return R.ok(configService.rollback(scene.toUpperCase(), type.toUpperCase(),
                version, UserContext.getUserId()));
    }

    // ------------------------------------------------------------------ 排查

    @ApiOperation("召回源清单与启用状态")
    @GetMapping("/recall-sources")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> recallSources(@RequestParam(defaultValue = "HOME_FEED") String scene) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", recallEngine.registeredSources());
        RecConfigService.RecallSetting setting = configService.recall(scene.toUpperCase());
        result.put("configured", setting == null ? null : setting.getSources());
        return R.ok(result);
    }

    @ApiOperation("召回源健康度（今日）")
    @GetMapping("/recall-status")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> recallStatus(@RequestParam(defaultValue = "0.5") double threshold) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", metricsService.today());
        result.put("sources", metricsService.recallStatus());
        result.put("unhealthy", metricsService.unhealthySources(threshold));
        result.put("degradeCount", metricsService.degradeCountToday());
        return R.ok(result);
    }

    @ApiOperation("按 traceId 还原推荐链路")
    @GetMapping("/trace/{traceId}")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> trace(@PathVariable String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshot", trackService.trace(traceId));
        result.put("log", logMapper.selectOne(Wrappers.<RecLog>lambdaQuery()
                .eq(RecLog::getTraceId, traceId).last("LIMIT 1")));
        return R.ok(result);
    }

    @ApiOperation("推荐请求日志")
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<PageResult<RecLog>> logs(PageQuery query,
                                      @RequestParam(required = false) String scene,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Integer degraded) {
        IPage<RecLog> page = logMapper.selectPage(query.toPage(), Wrappers.<RecLog>lambdaQuery()
                .eq(scene != null && !scene.isEmpty(), RecLog::getScene, scene)
                .eq(userId != null, RecLog::getUserId, userId)
                .eq(degraded != null, RecLog::getDegraded, degraded)
                .orderByDesc(RecLog::getCreateTime));
        return R.ok(PageResult.of(page, query.isTruncated()));
    }

    // ------------------------------------------------------------------ 模型 / 特征

    @ApiOperation("当前排序模型版本与系数")
    @GetMapping("/model")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> model(@RequestParam(defaultValue = "HOME_FEED") String scene) {
        return R.ok(fineRanker.modelSnapshot(scene.toUpperCase()));
    }

    @ApiOperation(value = "热重载模型", notes = "清掉进程内缓存，下一次请求从 Redis 重新加载。")
    @PostMapping("/model/reload")
    @PreAuthorize("hasAuthority('admin:rec:edit')")
    @AdminOpLog(type = "REC_MODEL_RELOAD", target = "REC_MODEL", description = "热重载推荐模型")
    public R<Void> reloadModel() {
        fineRanker.invalidateModel();
        return R.ok();
    }

    @ApiOperation("特征一致性抽检：在线特征 vs 离线口径")
    @GetMapping("/features/consistency")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> consistency(@RequestParam(defaultValue = "100") int sampleSize) {
        return R.ok(featureService.consistencySample(Math.max(10, Math.min(sampleSize, 1000))));
    }

    // ------------------------------------------------------------------ 新歌 / 监控

    @ApiOperation("新歌扶持状态")
    @GetMapping("/newsong/status")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> newSongStatus() {
        return R.ok(newSongService.status());
    }

    @ApiOperation(value = "手动停止/恢复新歌扶持", notes = "stop=true 停止，false 恢复。")
    @PostMapping("/newsong/{songId}/stop")
    @PreAuthorize("hasAuthority('admin:rec:edit')")
    @AdminOpLog(type = "REC_NEWSONG_STOP", target = "SONG", description = "停止/恢复新歌扶持")
    public R<Void> stopNewSong(@PathVariable Long songId,
                               @RequestParam(defaultValue = "true") boolean stop) {
        newSongService.stop(songId, stop);
        return R.ok();
    }

    @ApiOperation("推荐监控汇总")
    @GetMapping("/monitor")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> monitor() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", metricsService.today());
        result.put("recall", metricsService.recallStatus());
        result.put("unhealthy", metricsService.unhealthySources(0.5));
        result.put("degradeCount", metricsService.degradeCountToday());
        List<com.example.newmusic.module.recupgrade.entity.RecExperiment> running =
                experimentService.runningExperiments();
        result.put("runningExperiments", running.size());
        Map<String, Object> experimentNames = new LinkedHashMap<>();
        for (com.example.newmusic.module.recupgrade.entity.RecExperiment experiment : running) {
            experimentNames.put(experiment.getExperimentKey(), experiment.getName());
        }
        result.put("experiments", experimentNames);
        result.put("newSong", newSongService.status());
        return R.ok(result);
    }

    @ApiOperation(value = "手动聚合日报", notes = "默认聚合昨天；date 格式 yyyy-MM-dd。")
    @PostMapping("/metrics/aggregate")
    @PreAuthorize("hasAuthority('admin:rec:edit')")
    @AdminOpLog(type = "REC_METRIC_AGGREGATE", target = "REC_METRIC", description = "手动聚合推荐日报")
    public R<Map<String, Object>> aggregate(@RequestParam(required = false) String date) {
        java.time.LocalDate day = date == null || date.isEmpty()
                ? java.time.LocalDate.now().minusDays(1) : java.time.LocalDate.parse(date);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("groups", recJob.aggregate(day));
        return R.ok(result);
    }

    // ------------------------------------------------------------------ 离线评估

    @ApiOperation(value = "离线评估", notes = "严格按时间切分（前 N 天训练、最后 1 天测试），"
            + "输出 HitRate/NDCG/MAP/AUC/LogLoss/覆盖率/新颖度。")
    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> evaluate(@RequestParam(required = false) Integer maxUsers,
                                           @RequestParam(required = false) Integer trainDays) {
        return R.ok(evaluateService.evaluate(maxUsers, trainDays));
    }

    @ApiOperation("按 taskId 查询离线评估档案")
    @GetMapping("/evaluate/{taskId}")
    @PreAuthorize("hasAuthority('admin:rec:view')")
    public R<Map<String, Object>> evaluateResult(@PathVariable String taskId) {
        return R.ok(evaluateService.result(taskId));
    }
}
