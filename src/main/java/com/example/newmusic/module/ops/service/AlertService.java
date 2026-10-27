package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.dto.AlertRuleUpdateReq;
import com.example.newmusic.module.ops.dto.AlertSilenceReq;
import com.example.newmusic.module.ops.entity.AlertRecord;
import com.example.newmusic.module.ops.entity.AlertRule;
import com.example.newmusic.module.ops.entity.AlertSilence;
import com.example.newmusic.module.ops.mapper.AlertRecordMapper;
import com.example.newmusic.module.ops.mapper.AlertRuleMapper;
import com.example.newmusic.module.ops.mapper.AlertSilenceMapper;
import com.example.newmusic.module.ops.mapper.CostRecordMapper;
import com.example.newmusic.module.ops.metric.OpsMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警评估与处理（FR-28-007 / AC-28-007-4~6）。
 *
 * <p>刻意没有引入 Prometheus Alertmanager：本机没有 Prometheus，装了也只能空转。
 * 这里用「指标快照 + 规则比较 + for 持续时长」实现同等语义：
 * <ul>
 *   <li><b>for</b>：规则首次满足后不立即告警，持续 {@code durationSeconds} 仍满足才落库；</li>
 *   <li><b>去重</b>：同一规则同时只保留一条 firing 记录，恢复时自动 resolved；</li>
 *   <li><b>静默</b>：按规则名或级别静默，静默期内不产生 firing（仍留 silenced 记录可追溯）；</li>
 *   <li><b>自愈</b>：指标恢复后自动 resolved，不需要人工点「恢复」。</li>
 * </ul>
 *
 * <p>评估周期默认 5 秒（{@code newmusic.ops.alert-interval-ms}），可用配置中心
 * {@code ops.alert.eval.enabled} 整体暂停。评估异常只记日志，绝不让调度线程退出。
 */
@Slf4j
@Service
public class AlertService {

    private static final Set<String> OPERATORS = new HashSet<>(Arrays.asList("GT", "GE", "LT", "LE"));
    private static final Set<String> SEVERITIES = new HashSet<>(Arrays.asList("P0", "P1", "P2", "P3"));
    /** 与 SQL 种子里的 metric 字段一一对应；同时兼容早期的下划线别名。 */
    private static final Set<String> METRICS = new HashSet<>(Arrays.asList(
            "http.errorRate", "http.p99Ms", "db.available", "redis.available",
            "jvm.heapUsedRatio", "degrade.level", "rate.rejectedLastMin", "cost.budgetRatio",
            "disk.freeRatio", "chaos.running",
            "service_error_rate", "http_p99_latency", "database_unavailable", "redis_unavailable",
            "jvm_heap_high", "degrade_level_raised", "rate_limit_surge", "ai_cost_budget",
            "disk_space_low", "chaos_running"
    ));

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final AlertSilenceMapper silenceMapper;
    private final CostRecordMapper costRecordMapper;
    private final OpsMetricsService metricsService;
    private final OpsHealthService healthService;
    private final OpsConfigService configService;
    private final ChaosState chaosState;

    /** ruleId → 首次满足时间；不满足时移除。 */
    private final Map<Long, Long> pendingSince = new ConcurrentHashMap<>();

    private volatile String instance = "local";

    public AlertService(AlertRuleMapper ruleMapper, AlertRecordMapper recordMapper,
                        AlertSilenceMapper silenceMapper, CostRecordMapper costRecordMapper,
                        OpsMetricsService metricsService, OpsHealthService healthService,
                        OpsConfigService configService, ChaosState chaosState) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.silenceMapper = silenceMapper;
        this.costRecordMapper = costRecordMapper;
        this.metricsService = metricsService;
        this.healthService = healthService;
        this.configService = configService;
        this.chaosState = chaosState;
        try {
            instance = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 主机名拿不到就用 local，不影响告警
        }
    }

    @Scheduled(fixedDelayString = "${newmusic.ops.alert-interval-ms:5000}")
    public void scheduledEvaluate() {
        if (!configService.getBool(OpsConfigService.KEY_ALERT_EVAL_ENABLED, true)) {
            return;
        }
        try {
            evaluate();
        } catch (Exception e) {
            log.warn("告警评估失败 cause={}", e.toString());
        }
    }

    /** 幂等评估：可被调度与后台「立即评估」同时调用。 */
    public synchronized Map<String, Object> evaluate() {
        List<AlertRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getEnabled, 1));
        List<AlertSilence> silences = activeSilences();
        long now = System.currentTimeMillis();
        int fired = 0;
        int silenced = 0;
        int resolved = 0;
        List<AlertRecord> firedRecords = new ArrayList<>();
        for (AlertRule rule : rules) {
            try {
                double value = metricValue(rule.getMetric());
                boolean matched = rule.matches(value);
                if (!matched) {
                    pendingSince.remove(rule.getId());
                    resolved += resolveIfFiring(rule);
                    continue;
                }
                pendingSince.putIfAbsent(rule.getId(), now);
                long since = pendingSince.getOrDefault(rule.getId(), now);
                int forSeconds = rule.getDurationSeconds() == null ? 0 : rule.getDurationSeconds();
                if (now - since < forSeconds * 1000L) {
                    continue;
                }
                AlertSilence silence = matchSilence(silences, rule);
                AlertRecord open = findOpen(rule.getId());
                if (silence != null) {
                    if (open == null) {
                        AlertRecord record = buildRecord(rule, value, AlertRecord.STATUS_SILENCED);
                        record.setSummary("（静默中）" + record.getSummary());
                        recordMapper.insert(record);
                        silenced++;
                        firedRecords.add(record);
                    }
                    continue;
                }
                if (open != null) {
                    // 已在告警中：只刷新指标值，不重复落库；
                    // 若静默已到期，把 silenced 提升为 firing，避免「静默一次就永久哑火」
                    open.setMetricValue(value);
                    open.setThresholdValue(rule.getThresholdValue());
                    if (AlertRecord.STATUS_SILENCED.equals(open.getStatus())) {
                        open.setStatus(AlertRecord.STATUS_FIRING);
                        open.setFireTime(LocalDateTime.now());
                        open.setSummary(open.getSummary() == null ? null
                                : open.getSummary().replace("（静默中）", ""));
                        fired++;
                        log.warn("告警静默到期后触发 rule={} metric={} value={}", rule.getRuleName(),
                                rule.getMetric(), value);
                    }
                    recordMapper.updateById(open);
                    continue;
                }
                AlertRecord record = buildRecord(rule, value, AlertRecord.STATUS_FIRING);
                recordMapper.insert(record);
                fired++;
                firedRecords.add(record);
                log.warn("告警触发 rule={} metric={} value={} threshold={} severity={}",
                        rule.getRuleName(), rule.getMetric(), value, rule.getThresholdValue(),
                        rule.getSeverity());
            } catch (Exception e) {
                log.warn("告警规则评估失败 rule={} cause={}", rule.getRuleName(), e.toString());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluated", rules.size());
        result.put("fired", fired);
        result.put("silenced", silenced);
        result.put("resolved", resolved);
        result.put("records", firedRecords);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    // ------------------------------------------------------------------ 规则

    public List<AlertRule> rules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .orderByAsc(AlertRule::getSeverity)
                .orderByAsc(AlertRule::getId));
    }

    @Transactional
    public AlertRule updateRule(Long id, AlertRuleUpdateReq req) {
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ResultCode.OPS_ALERT_NOT_FOUND);
        }
        if (req.getOperator() != null) {
            String operator = req.getOperator().toUpperCase();
            if (!OPERATORS.contains(operator)) {
                throw new BizException(ResultCode.OPS_ALERT_RULE_INVALID, "operator 只支持 GT/GE/LT/LE");
            }
            rule.setOperator(operator);
        }
        if (req.getSeverity() != null) {
            String severity = req.getSeverity().toUpperCase();
            if (!SEVERITIES.contains(severity)) {
                throw new BizException(ResultCode.OPS_ALERT_RULE_INVALID, "severity 只支持 P0~P3");
            }
            rule.setSeverity(severity);
        }
        if (!METRICS.contains(rule.getMetric())) {
            throw new BizException(ResultCode.OPS_ALERT_RULE_INVALID, "未知指标 " + rule.getMetric());
        }
        rule.setThresholdValue(req.getThresholdValue());
        if (req.getDurationSeconds() != null) {
            rule.setDurationSeconds(req.getDurationSeconds());
        }
        if (req.getEnabled() != null) {
            rule.setEnabled(req.getEnabled() == 0 ? 0 : 1);
        }
        rule.setRunbookKey(req.getRunbookKey());
        if (req.getDescription() != null) {
            rule.setDescription(req.getDescription());
        }
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.updateById(rule);
        pendingSince.remove(id);
        return rule;
    }

    // ------------------------------------------------------------------ 记录

    public Page<AlertRecord> records(String status, String severity, int page, int size) {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(AlertRecord::getStatus, status);
        }
        if (StringUtils.hasText(severity)) {
            wrapper.eq(AlertRecord::getSeverity, severity);
        }
        wrapper.orderByDesc(AlertRecord::getFireTime).orderByDesc(AlertRecord::getId);
        return recordMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                wrapper);
    }

    public long firingCount() {
        return recordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getStatus, AlertRecord.STATUS_FIRING));
    }

    public long recentCount(int hours) {
        return recordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                .ge(AlertRecord::getFireTime, LocalDateTime.now().minusHours(Math.max(1, hours))));
    }

    @Transactional
    public AlertRecord handle(Long id, String note) {
        AlertRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BizException(ResultCode.OPS_ALERT_NOT_FOUND);
        }
        LoginUser user = UserContext.get();
        record.setHandlerId(user == null ? null : user.getUserId());
        record.setHandlerName(user == null ? "system"
                : (StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername()));
        record.setHandleNote(note);
        record.setStatus(AlertRecord.STATUS_RESOLVED);
        record.setResolveTime(LocalDateTime.now());
        recordMapper.updateById(record);
        if (record.getRuleId() != null) {
            pendingSince.remove(record.getRuleId());
        }
        return record;
    }

    // ------------------------------------------------------------------ 静默

    public List<AlertSilence> silences() {
        return silenceMapper.selectList(new LambdaQueryWrapper<AlertSilence>()
                .gt(AlertSilence::getUntilTime, LocalDateTime.now())
                .orderByDesc(AlertSilence::getId));
    }

    @Transactional
    public AlertSilence silence(AlertSilenceReq req) {
        if (!StringUtils.hasText(req.getRuleName()) && !StringUtils.hasText(req.getSeverity())) {
            throw new BizException(ResultCode.OPS_ALERT_RULE_INVALID, "ruleName 与 severity 至少填一个");
        }
        AlertSilence silence = new AlertSilence();
        silence.setRuleName(StringUtils.hasText(req.getRuleName()) ? req.getRuleName().trim() : null);
        silence.setSeverity(StringUtils.hasText(req.getSeverity())
                ? req.getSeverity().toUpperCase() : null);
        if (silence.getSeverity() != null && !SEVERITIES.contains(silence.getSeverity())) {
            throw new BizException(ResultCode.OPS_ALERT_RULE_INVALID, "severity 只支持 P0~P3");
        }
        silence.setUntilTime(LocalDateTime.now().plusMinutes(req.getDurationMinutes()));
        silence.setReason(req.getReason());
        silence.setOperatorId(UserContext.getUserId());
        silence.setCreateTime(LocalDateTime.now());
        silenceMapper.insert(silence);
        log.warn("告警静默 rule={} severity={} until={} operator={}", silence.getRuleName(),
                silence.getSeverity(), silence.getUntilTime(), silence.getOperatorId());
        return silence;
    }

    @Transactional
    public void cancelSilence(Long id) {
        AlertSilence silence = silenceMapper.selectById(id);
        if (silence == null) {
            throw new BizException(ResultCode.OPS_ALERT_NOT_FOUND);
        }
        silence.setUntilTime(LocalDateTime.now());
        silenceMapper.updateById(silence);
    }

    // ------------------------------------------------------------------ 内部

    private List<AlertSilence> activeSilences() {
        try {
            return silenceMapper.selectList(new LambdaQueryWrapper<AlertSilence>()
                    .gt(AlertSilence::getUntilTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("读取静默配置失败，按无静默处理 cause={}", e.toString());
            return new ArrayList<>();
        }
    }

    private AlertSilence matchSilence(List<AlertSilence> silences, AlertRule rule) {
        for (AlertSilence silence : silences) {
            if (StringUtils.hasText(silence.getRuleName())
                    && silence.getRuleName().equals(rule.getRuleName())) {
                return silence;
            }
            if (!StringUtils.hasText(silence.getRuleName())
                    && StringUtils.hasText(silence.getSeverity())
                    && silence.getSeverity().equalsIgnoreCase(rule.getSeverity())) {
                return silence;
            }
        }
        return null;
    }

    private AlertRecord findOpen(Long ruleId) {
        List<AlertRecord> list = recordMapper.selectList(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleId, ruleId)
                .in(AlertRecord::getStatus, AlertRecord.STATUS_FIRING, AlertRecord.STATUS_SILENCED)
                .orderByDesc(AlertRecord::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private int resolveIfFiring(AlertRule rule) {
        List<AlertRecord> list = recordMapper.selectList(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleId, rule.getId())
                .in(AlertRecord::getStatus, AlertRecord.STATUS_FIRING, AlertRecord.STATUS_SILENCED));
        int count = 0;
        for (AlertRecord record : list) {
            record.setStatus(AlertRecord.STATUS_RESOLVED);
            record.setResolveTime(LocalDateTime.now());
            recordMapper.updateById(record);
            count++;
        }
        return count;
    }

    private AlertRecord buildRecord(AlertRule rule, double value, String status) {
        AlertRecord record = new AlertRecord();
        record.setRuleId(rule.getId());
        record.setAlertName(rule.getRuleName());
        record.setSeverity(rule.getSeverity());
        record.setInstance(instance);
        record.setMetricValue(value);
        record.setThresholdValue(rule.getThresholdValue());
        record.setStatus(status);
        record.setFireTime(LocalDateTime.now());
        record.setDedupKey("rule:" + rule.getId());
        record.setSummary(rule.getRuleName() + "：" + rule.getMetric() + "=" + round(value)
                + "，" + rule.getOperator() + " " + rule.getThresholdValue()
                + "（持续 " + rule.getDurationSeconds() + "s）");
        record.setCreateTime(LocalDateTime.now());
        return record;
    }

    /** 指标名 → 当前值；未知指标返回 0，并在规则更新时被拦截。 */
    private double metricValue(String metric) {
        switch (metric) {
            case "http.errorRate":
            case "service_error_rate":
                return metricsService.errorRate(60);
            case "http.p99Ms":
            case "http_p99_latency":
                return metricsService.p99LatencyMs();
            case "db.available":
            case "database_unavailable":
                return healthService.dbAvailable() ? 1d : 0d;
            case "redis.available":
            case "redis_unavailable":
                return metricsService.redisAvailable() ? 1d : 0d;
            case "jvm.heapUsedRatio":
            case "jvm_heap_high":
                return metricsService.heapUsageRatio();
            case "degrade.level":
            case "degrade_level_raised":
                return metricsService.currentDegradeLevel();
            case "rate.rejectedLastMin":
            case "rate_limit_surge":
                return metricsService.rateLimitedInWindow(60);
            case "cost.budgetRatio":
            case "ai_cost_budget": {
                long budget = costRecordMapper.budgetToday();
                if (budget <= 0) {
                    budget = configService.getLong(OpsConfigService.KEY_COST_DAILY_BUDGET_CENT, 0L);
                }
                return budget <= 0 ? 0d : (double) costRecordMapper.sumToday() / budget;
            }
            case "disk.freeRatio":
            case "disk_space_low":
                return metricsService.diskAvailableRatio();
            case "chaos.running":
            case "chaos_running":
                return chaosState.snapshot() != null ? 1d : 0d;
            default:
                return 0d;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /** 给大盘用的聚合。 */
    public Map<String, Object> overview() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("firing", firingCount());
        map.put("last24h", recentCount(24));
        map.put("rules", ruleMapper.selectCount(new QueryWrapper<>()));
        map.put("silences", silences().size());
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }
}
