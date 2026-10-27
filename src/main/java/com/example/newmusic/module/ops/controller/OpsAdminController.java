package com.example.newmusic.module.ops.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.SysConfigUpdateReq;
import com.example.newmusic.module.admin.service.SysConfigService;
import com.example.newmusic.module.ops.breaker.CircuitBreakerService;
import com.example.newmusic.module.ops.dto.AlertHandleReq;
import com.example.newmusic.module.ops.dto.AlertRuleUpdateReq;
import com.example.newmusic.module.ops.dto.AlertSilenceReq;
import com.example.newmusic.module.ops.dto.BackupReq;
import com.example.newmusic.module.ops.dto.ChaosStartReq;
import com.example.newmusic.module.ops.dto.CostRecordReq;
import com.example.newmusic.module.ops.dto.DegradeLevelReq;
import com.example.newmusic.module.ops.dto.DegradeSwitchUpdateReq;
import com.example.newmusic.module.ops.dto.PublishReq;
import com.example.newmusic.module.ops.dto.RateLimitRuleUpdateReq;
import com.example.newmusic.module.ops.dto.RollbackReq;
import com.example.newmusic.module.ops.entity.AlertRecord;
import com.example.newmusic.module.ops.entity.AlertRule;
import com.example.newmusic.module.ops.entity.AlertSilence;
import com.example.newmusic.module.ops.entity.BackupRecord;
import com.example.newmusic.module.ops.entity.ChaosExperiment;
import com.example.newmusic.module.ops.entity.CostRecord;
import com.example.newmusic.module.ops.entity.DegradeSwitch;
import com.example.newmusic.module.ops.entity.OpsRunbook;
import com.example.newmusic.module.ops.entity.PublishRecord;
import com.example.newmusic.module.ops.metric.OpsMetricsService;
import com.example.newmusic.module.ops.metric.OpsTraceBuffer;
import com.example.newmusic.module.ops.service.AlertService;
import com.example.newmusic.module.ops.service.BackupService;
import com.example.newmusic.module.ops.service.ChaosService;
import com.example.newmusic.module.ops.service.CostService;
import com.example.newmusic.module.ops.service.DegradeService;
import com.example.newmusic.module.ops.service.MetricSnapshotService;
import com.example.newmusic.module.ops.service.OpsConfigService;
import com.example.newmusic.module.ops.service.OpsHealthService;
import com.example.newmusic.module.ops.service.PublishService;
import com.example.newmusic.module.ops.service.RateLimitRuleService;
import com.example.newmusic.module.ops.service.RedisInspectService;
import com.example.newmusic.module.ops.service.RunbookService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维后台接口（模块 19）。
 *
 * <p>统一挂在 {@code /api/v1/admin/ops/**} 下：既避开已有的
 * {@code /api/v1/admin/metrics}（模块 17 数据平台），也让「这批能力属于运维面」一目了然。
 * 配置中心仍复用模块 09 的 {@code /api/v1/admin/configs/{idOrKey}}。
 */
@Api(tags = "后台接口-高可用与运维")
@RestController
@RequestMapping("/api/v1/admin/ops")
public class OpsAdminController {

    private final OpsHealthService healthService;
    private final OpsMetricsService metricsService;
    private final OpsTraceBuffer traceBuffer;
    private final RateLimitRuleService rateLimitRuleService;
    private final CircuitBreakerService circuitBreakerService;
    private final DegradeService degradeService;
    private final AlertService alertService;
    private final PublishService publishService;
    private final BackupService backupService;
    private final CostService costService;
    private final ChaosService chaosService;
    private final RunbookService runbookService;
    private final RedisInspectService redisInspectService;
    private final MetricSnapshotService snapshotService;
    private final OpsConfigService opsConfigService;
    private final SysConfigService sysConfigService;

    public OpsAdminController(OpsHealthService healthService, OpsMetricsService metricsService,
                              OpsTraceBuffer traceBuffer, RateLimitRuleService rateLimitRuleService,
                              CircuitBreakerService circuitBreakerService, DegradeService degradeService,
                              AlertService alertService, PublishService publishService,
                              BackupService backupService, CostService costService,
                              ChaosService chaosService, RunbookService runbookService,
                              RedisInspectService redisInspectService,
                              MetricSnapshotService snapshotService,
                              OpsConfigService opsConfigService, SysConfigService sysConfigService) {
        this.healthService = healthService;
        this.metricsService = metricsService;
        this.traceBuffer = traceBuffer;
        this.rateLimitRuleService = rateLimitRuleService;
        this.circuitBreakerService = circuitBreakerService;
        this.degradeService = degradeService;
        this.alertService = alertService;
        this.publishService = publishService;
        this.backupService = backupService;
        this.costService = costService;
        this.chaosService = chaosService;
        this.runbookService = runbookService;
        this.redisInspectService = redisInspectService;
        this.snapshotService = snapshotService;
        this.opsConfigService = opsConfigService;
        this.sysConfigService = sysConfigService;
    }

    // ------------------------------------------------------------------ 总览

    @ApiOperation("运维总览")
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, Object>> overview() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("health", healthService.health());
        map.put("metrics", metricsService.snapshot());
        map.put("alerts", alertService.overview());
        map.put("degrade", degradeService.publicStatus());
        map.put("rateLimits", rateLimitRuleService.list());
        map.put("circuits", circuitBreakerService.snapshot());
        map.put("chaos", chaosService.current());
        map.put("trace", traceBuffer.stats());
        return R.ok(map);
    }

    @ApiOperation("聚合健康检查")
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, Object>> health() {
        return R.ok(healthService.health());
    }

    // ------------------------------------------------------------------ 限流

    @ApiOperation("限流规则列表")
    @GetMapping("/rate-limits")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<com.example.newmusic.module.ops.dto.RateLimitRuleVO>> rateLimits() {
        return R.ok(rateLimitRuleService.list());
    }

    @ApiOperation("修改限流规则")
    @PutMapping("/rate-limits/{resource}")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_RATE_LIMIT_UPDATE", target = "RATE_LIMIT_RULE",
            description = "修改限流规则")
    public R<com.example.newmusic.module.ops.dto.RateLimitRuleVO> updateRateLimit(
            @PathVariable String resource, @RequestBody @Valid RateLimitRuleUpdateReq req) {
        return R.ok(rateLimitRuleService.update(resource, req, UserContext.getUserId()));
    }

    @ApiOperation("清零限流命中计数")
    @PostMapping("/rate-limits/counters/reset")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    public R<Void> resetRateLimitCounters() {
        rateLimitRuleService.resetCounters();
        return R.ok();
    }

    @ApiOperation("熔断器状态")
    @GetMapping("/circuit-breakers")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<Map<String, Object>>> circuitBreakers() {
        return R.ok(circuitBreakerService.snapshot());
    }

    // ------------------------------------------------------------------ 降级

    @ApiOperation("当前降级状态")
    @GetMapping("/degrade")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, Object>> degradeStatus() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", degradeService.currentLevel());
        map.put("levelName", degradeService.currentLevelName());
        map.put("switches", degradeService.switchViews());
        return R.ok(map);
    }

    @ApiOperation("一键降级")
    @PostMapping("/degrade/level")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_DEGRADE_LEVEL", target = "DEGRADE_SWITCH", description = "一键降级")
    public R<Map<String, Object>> setDegradeLevel(@RequestBody @Valid DegradeLevelReq req) {
        int changed = degradeService.setLevel(req.getLevel(), req.getReason());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", degradeService.currentLevel());
        map.put("levelName", degradeService.currentLevelName());
        map.put("switchesChanged", changed);
        return R.ok(map);
    }

    @ApiOperation("降级开关列表")
    @GetMapping("/degrade-switches")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<com.example.newmusic.module.ops.dto.DegradeSwitchVO>> degradeSwitches() {
        return R.ok(degradeService.switchViews());
    }

    @ApiOperation("单独开关降级项")
    @PutMapping("/degrade-switches/{key}")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_DEGRADE_SWITCH", target = "DEGRADE_SWITCH", description = "开关降级项")
    public R<DegradeSwitch> updateDegradeSwitch(@PathVariable String key,
                                                @RequestBody @Valid DegradeSwitchUpdateReq req) {
        degradeService.setSwitch(key, Boolean.TRUE.equals(req.getEnabled()), req.getRemark());
        return R.ok(degradeService.switches().stream()
                .filter(item -> key.equals(item.getSwitchKey())).findFirst().orElse(null));
    }

    // ------------------------------------------------------------------ 告警

    @ApiOperation("告警规则")
    @GetMapping("/alerts/rules")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<AlertRule>> alertRules() {
        return R.ok(alertService.rules());
    }

    @ApiOperation("修改告警规则")
    @PutMapping("/alerts/rules/{id}")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_ALERT_RULE_UPDATE", target = "ALERT_RULE", description = "修改告警规则")
    public R<AlertRule> updateAlertRule(@PathVariable Long id,
                                        @RequestBody @Valid AlertRuleUpdateReq req) {
        return R.ok(alertService.updateRule(id, req));
    }

    @ApiOperation("告警记录")
    @GetMapping("/alerts/records")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<PageResult<AlertRecord>> alertRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.of(alertService.records(status, severity, page, size)));
    }

    @ApiOperation("处理告警")
    @PostMapping("/alerts/records/{id}/handle")
    @PreAuthorize("hasAuthority('admin:ops:alert:handle')")
    @AdminOpLog(type = "OPS_ALERT_HANDLE", target = "ALERT_RECORD", description = "处理告警")
    public R<AlertRecord> handleAlert(@PathVariable Long id,
                                      @RequestBody(required = false) AlertHandleReq req) {
        return R.ok(alertService.handle(id, req == null ? null : req.getNote()));
    }

    @ApiOperation(value = "立即评估一次告警", notes = "验收与排障用；调度任务每 5 秒自动评估")
    @PostMapping("/alerts/evaluate")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    public R<Map<String, Object>> evaluateAlerts() {
        return R.ok(alertService.evaluate());
    }

    @ApiOperation("告警概况")
    @GetMapping("/alerts/overview")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, Object>> alertOverview() {
        return R.ok(alertService.overview());
    }

    @ApiOperation("告警静默列表")
    @GetMapping("/alerts/silences")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<AlertSilence>> alertSilences() {
        return R.ok(alertService.silences());
    }

    @ApiOperation("新增告警静默")
    @PostMapping("/alerts/silences")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_ALERT_SILENCE", target = "ALERT_SILENCE", description = "告警静默")
    public R<AlertSilence> silence(@RequestBody @Valid AlertSilenceReq req) {
        return R.ok(alertService.silence(req));
    }

    @ApiOperation("取消告警静默")
    @DeleteMapping("/alerts/silences/{id}")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    public R<Void> cancelSilence(@PathVariable Long id) {
        alertService.cancelSilence(id);
        return R.ok();
    }

    // ------------------------------------------------------------------ 指标 / trace

    @ApiOperation("指标快照")
    @GetMapping("/metrics")
    @PreAuthorize("hasAuthority('admin:ops:metrics:view')")
    public R<Map<String, Object>> metrics() {
        return R.ok(metricsService.snapshot());
    }

    @ApiOperation("Prometheus 文本指标")
    @GetMapping("/metrics/prometheus")
    @PreAuthorize("hasAuthority('admin:ops:metrics:view')")
    public R<String> prometheus() {
        return R.ok(metricsService.prometheus());
    }

    @ApiOperation("路由维度指标")
    @GetMapping("/metrics/routes")
    @PreAuthorize("hasAuthority('admin:ops:metrics:view')")
    public R<List<Map<String, Object>>> routes(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(metricsService.routeStats(limit));
    }

    @ApiOperation("线程池水位")
    @GetMapping("/metrics/threadpools")
    @PreAuthorize("hasAuthority('admin:ops:metrics:view')")
    public R<List<Map<String, Object>>> threadPools() {
        return R.ok(metricsService.threadPools());
    }

    @ApiOperation("指标趋势（落库快照）")
    @GetMapping("/metrics/history")
    @PreAuthorize("hasAuthority('admin:ops:metrics:view')")
    public R<Map<String, Object>> metricHistory(
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("aggregate", snapshotService.aggregate(minutes));
        if (metric != null && !metric.isEmpty()) {
            map.put("items", snapshotService.history(metric, limit));
        }
        return R.ok(map);
    }

    @ApiOperation("链路记录检索")
    @GetMapping("/traces")
    @PreAuthorize("hasAuthority('admin:ops:trace:view')")
    public R<Map<String, Object>> traces(@RequestParam(required = false) String traceId,
                                         @RequestParam(required = false) Long userId,
                                         @RequestParam(required = false) String path,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) Long minDurationMs,
                                         @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", traceBuffer.search(traceId, userId, path, status, minDurationMs, limit));
        map.put("stats", traceBuffer.stats());
        return R.ok(map);
    }

    // ------------------------------------------------------------------ 发布

    @ApiOperation("发布记录")
    @GetMapping("/publish")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<PageResult<PublishRecord>> publishRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.of(publishService.list(status, service, page, size)));
    }

    @ApiOperation(value = "创建发布记录", notes = "本机不执行真实部署，只维护状态机与审计记录")
    @PostMapping("/publish")
    @PreAuthorize("hasAuthority('admin:ops:publish')")
    @AdminOpLog(type = "OPS_PUBLISH_CREATE", target = "PUBLISH_RECORD", description = "创建发布")
    public R<PublishRecord> createPublish(@RequestBody @Valid PublishReq req) {
        return R.ok(publishService.create(req));
    }

    @ApiOperation("完成发布")
    @PostMapping("/publish/{id}/finish")
    @PreAuthorize("hasAuthority('admin:ops:publish')")
    @AdminOpLog(type = "OPS_PUBLISH_FINISH", target = "PUBLISH_RECORD", description = "完成发布")
    public R<PublishRecord> finishPublish(@PathVariable Long id,
                                          @RequestParam(defaultValue = "true") boolean success,
                                          @RequestParam(required = false) String message) {
        return R.ok(publishService.finish(id, success, message));
    }

    @ApiOperation("回滚发布")
    @PostMapping("/publish/{id}/rollback")
    @PreAuthorize("hasAuthority('admin:ops:publish')")
    @AdminOpLog(type = "OPS_PUBLISH_ROLLBACK", target = "PUBLISH_RECORD", description = "回滚发布")
    public R<Map<String, Object>> rollback(@PathVariable Long id,
                                           @RequestBody(required = false) RollbackReq req) {
        return R.ok(publishService.rollback(id, req == null ? new RollbackReq() : req));
    }

    // ------------------------------------------------------------------ 备份

    @ApiOperation("备份记录")
    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<PageResult<BackupRecord>> backups(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.of(backupService.list(status, page, size)));
    }

    @ApiOperation(value = "生成备份清单", notes = "逻辑清单 + SHA-256；不执行 mysqldump")
    @PostMapping("/backups")
    @PreAuthorize("hasAuthority('admin:ops:backup')")
    @AdminOpLog(type = "OPS_BACKUP_CREATE", target = "BACKUP_RECORD", description = "生成备份清单")
    public R<BackupRecord> createBackup(@RequestBody(required = false) BackupReq req) {
        return R.ok(backupService.trigger(req == null ? new BackupReq() : req));
    }

    @ApiOperation("校验备份")
    @PostMapping("/backups/{id}/verify")
    @PreAuthorize("hasAuthority('admin:ops:backup')")
    public R<Map<String, Object>> verifyBackup(@PathVariable Long id) {
        return R.ok(backupService.verify(id));
    }

    @ApiOperation("预览备份清单")
    @GetMapping("/backups/{id}/preview")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<String>> previewBackup(@PathVariable Long id,
                                         @RequestParam(defaultValue = "50") int limit) {
        return R.ok(backupService.preview(id, limit));
    }

    // ------------------------------------------------------------------ 成本

    @ApiOperation("成本概况")
    @GetMapping("/costs")
    @PreAuthorize("hasAuthority('admin:cost:view')")
    public R<Map<String, Object>> costs() {
        return R.ok(costService.overview());
    }

    @ApiOperation("成本明细")
    @GetMapping("/costs/records")
    @PreAuthorize("hasAuthority('admin:cost:view')")
    public R<List<CostRecord>> costRecords(@RequestParam(defaultValue = "30") int days,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(defaultValue = "200") int limit) {
        return R.ok(costService.records(days, category, limit));
    }

    @ApiOperation("补录成本")
    @PostMapping("/costs/records")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    public R<CostRecord> addCost(@RequestBody @Valid CostRecordReq req) {
        return R.ok(costService.add(req));
    }

    // ------------------------------------------------------------------ 混沌

    @ApiOperation("混沌实验列表")
    @GetMapping("/chaos/experiments")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<PageResult<ChaosExperiment>> chaosExperiments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.of(chaosService.list(status, page, size)));
    }

    @ApiOperation("当前混沌实验")
    @GetMapping("/chaos/current")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<ChaosExperiment> chaosCurrent() {
        return R.ok(chaosService.current());
    }

    @ApiOperation("启动混沌实验")
    @PostMapping("/chaos/experiments")
    @PreAuthorize("hasAuthority('admin:ops:chaos')")
    @AdminOpLog(type = "OPS_CHAOS_START", target = "CHAOS_EXPERIMENT", description = "启动混沌实验")
    public R<ChaosExperiment> startChaos(@RequestBody @Valid ChaosStartReq req) {
        return R.ok(chaosService.start(req));
    }

    @ApiOperation("停止混沌实验")
    @PostMapping("/chaos/stop")
    @PreAuthorize("hasAuthority('admin:ops:chaos')")
    @AdminOpLog(type = "OPS_CHAOS_STOP", target = "CHAOS_EXPERIMENT", description = "停止混沌实验")
    public R<Map<String, Object>> stopChaos(@RequestParam(required = false) String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stopped", chaosService.stop(reason));
        return R.ok(map);
    }

    // ------------------------------------------------------------------ 预案 / 缓存 / 配置

    @ApiOperation("应急预案列表")
    @GetMapping("/runbooks")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<List<OpsRunbook>> runbooks(@RequestParam(required = false) String severity) {
        return R.ok(runbookService.list(severity));
    }

    @ApiOperation("应急预案详情")
    @GetMapping("/runbooks/{key}")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<OpsRunbook> runbook(@PathVariable String key) {
        return R.ok(runbookService.get(key));
    }

    @ApiOperation(value = "缓存键巡检", notes = "只允许 ml: 前缀，SCAN 枚举")
    @GetMapping("/cache/keys")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, Object>> cacheKeys(@RequestParam(defaultValue = "ml:") String prefix,
                                            @RequestParam(defaultValue = "100") int limit) {
        return R.ok(redisInspectService.inspect(prefix, limit));
    }

    @ApiOperation(value = "按前缀清理缓存", notes = "confirm 必须等于前缀；前缀至少两段")
    @DeleteMapping("/cache/keys")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_CACHE_DELETE", target = "CACHE", description = "清理缓存前缀")
    public R<Map<String, Object>> deleteCache(@RequestParam String prefix,
                                              @RequestParam String confirm) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deleted", redisInspectService.deleteByPrefix(prefix, confirm));
        return R.ok(map);
    }

    @ApiOperation("运维相关配置")
    @GetMapping("/configs")
    @PreAuthorize("hasAuthority('admin:ops:view')")
    public R<Map<String, String>> configs() {
        return R.ok(opsConfigService.all());
    }

    @ApiOperation(value = "热更新运维配置", notes = "写 t_sys_config 与变更历史，2~5 秒内生效")
    @PutMapping("/configs/{key}")
    @PreAuthorize("hasAuthority('admin:ops:edit')")
    @AdminOpLog(type = "OPS_CONFIG_UPDATE", target = "SYS_CONFIG", description = "修改运维配置",
            sensitive = true)
    public R<Void> updateConfig(@PathVariable String key,
                                @RequestBody @Valid SysConfigUpdateReq req) {
        sysConfigService.updateByKey(key, req);
        opsConfigService.invalidate();
        return R.ok();
    }
}
