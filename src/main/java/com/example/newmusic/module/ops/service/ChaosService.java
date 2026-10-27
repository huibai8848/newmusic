package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.dto.ChaosStartReq;
import com.example.newmusic.module.ops.entity.ChaosExperiment;
import com.example.newmusic.module.ops.mapper.ChaosExperimentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 混沌实验（FR-28-011 / AC-28-011-6~8）。
 *
 * <p>三个故障点都是**应用内可安全注入**的，不碰共享 MySQL/Redis 实例本身：
 * <ul>
 *   <li>{@code REDIS_LIMITER_UNAVAILABLE}：让限流器走「Redis 失败」分支，验证本地兜底；</li>
 *   <li>{@code AI_TIMEOUT}：AI 调用按爆炸半径直接超时，验证熔断与降级文案；</li>
 *   <li>{@code SLOW_DB}：SQL 执行前注入最多 1 秒延迟，验证慢 SQL 指标与线程池水位。</li>
 * </ul>
 *
 * <p><b>硬上限：</b>时长 ≤ {@code chaos-max-duration-seconds}（默认 120s），
 * 爆炸半径 ≤ {@code chaos-max-blast-radius}（默认 100），同一时刻只允许一个实验。
 * 到点由 {@link #sweep()} 自动结束并落库，不依赖操作人记得关。
 */
@Slf4j
@Service
public class ChaosService {

    private static final Set<String> FAULTS = new HashSet<>(Arrays.asList(
            ChaosState.FAULT_REDIS_LIMITER, ChaosState.FAULT_AI_TIMEOUT, ChaosState.FAULT_SLOW_DB));

    private final ChaosExperimentMapper mapper;
    private final ChaosState chaosState;
    private final OpsProperties properties;
    private final OpsConfigService configService;
    private final com.example.newmusic.module.ops.metric.OpsMetricsService metricsService;

    public ChaosService(ChaosExperimentMapper mapper, ChaosState chaosState,
                        OpsProperties properties, OpsConfigService configService,
                        com.example.newmusic.module.ops.metric.OpsMetricsService metricsService) {
        this.mapper = mapper;
        this.chaosState = chaosState;
        this.properties = properties;
        this.configService = configService;
        this.metricsService = metricsService;
    }

    @Transactional
    public ChaosExperiment start(ChaosStartReq req) {
        boolean enabled = properties.isChaosEnabled()
                && configService.getBool(OpsConfigService.KEY_CHAOS_ENABLED, true);
        if (!enabled) {
            throw new BizException(ResultCode.OPS_CHAOS_DISABLED);
        }
        if (chaosState.snapshot() != null) {
            throw new BizException(ResultCode.OPS_CHAOS_LIMIT, "已有运行中的混沌实验，请先停止");
        }
        String fault = req.getFaultType().toUpperCase();
        if (!FAULTS.contains(fault)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "faultType 只支持 " + FAULTS);
        }
        int radius = Math.min(properties.getChaosMaxBlastRadius(),
                req.getBlastRadius() == null ? 10 : req.getBlastRadius());
        int duration = Math.min(properties.getChaosMaxDurationSeconds(),
                req.getDurationSeconds() == null ? 30 : req.getDurationSeconds());
        if (radius < 1 || duration < 1) {
            throw new BizException(ResultCode.OPS_CHAOS_LIMIT, "爆炸半径与时长必须大于 0");
        }

        ChaosExperiment experiment = new ChaosExperiment();
        experiment.setExperimentName(req.getExperimentName());
        experiment.setTarget(req.getTarget());
        experiment.setFaultType(fault);
        experiment.setHypothesis(req.getHypothesis());
        experiment.setBlastRadius(radius);
        experiment.setDurationSeconds(duration);
        experiment.setStatus(ChaosExperiment.STATUS_RUNNING);
        experiment.setOperatorId(UserContext.getUserId());
        experiment.setStartTime(LocalDateTime.now());
        experiment.setCreateTime(LocalDateTime.now());
        mapper.insert(experiment);
        chaosState.start(experiment);
        log.warn("混沌实验启动 id={} fault={} radius={}% duration={}s operator={}",
                experiment.getId(), fault, radius, duration, experiment.getOperatorId());
        return experiment;
    }

    @Transactional
    public int stop(String reason) {
        List<ChaosExperiment> running = mapper.selectList(new LambdaQueryWrapper<ChaosExperiment>()
                .eq(ChaosExperiment::getStatus, ChaosExperiment.STATUS_RUNNING));
        LocalDateTime now = LocalDateTime.now();
        for (ChaosExperiment experiment : running) {
            experiment.setStatus(ChaosExperiment.STATUS_STOPPED);
            experiment.setEndTime(now);
            // result 列只有 VARCHAR(16)，人工输入的 reason 必须截断，否则整条 UPDATE 失败
            String result = reason == null ? "人工停止" : reason;
            experiment.setResult(result.length() > 16 ? result.substring(0, 16) : result);
            mapper.updateById(experiment);
        }
        chaosState.stop();
        if (!running.isEmpty()) {
            log.warn("混沌实验停止 count={} reason={}", running.size(), reason);
        }
        return running.size();
    }

    /** 到点自动结束；1 秒一次，成本可忽略。 */
    @Scheduled(fixedDelay = 1000L)
    public void sweep() {
        Long expiredId = chaosState.expiredExperimentId();
        if (expiredId == null) {
            return;
        }
        try {
            ChaosExperiment experiment = mapper.selectById(expiredId);
            if (experiment != null && ChaosExperiment.STATUS_RUNNING.equals(experiment.getStatus())) {
                experiment.setStatus(ChaosExperiment.STATUS_FINISHED);
                experiment.setEndTime(LocalDateTime.now());
                experiment.setResult("到达时长上限，自动结束");
                mapper.updateById(experiment);
            }
            chaosState.stop();
            log.info("混沌实验到期自动结束 id={}", expiredId);
        } catch (Exception e) {
            chaosState.stop();
            log.warn("混沌实验自动结束落库失败 id={} cause={}", expiredId, e.toString());
        }
    }

    public Page<ChaosExperiment> list(String status, int page, int size) {
        LambdaQueryWrapper<ChaosExperiment> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(ChaosExperiment::getStatus, status);
        }
        wrapper.orderByDesc(ChaosExperiment::getStartTime).orderByDesc(ChaosExperiment::getId);
        return mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                wrapper);
    }

    public ChaosExperiment current() {
        return chaosState.snapshot();
    }

    /** 供演练报告使用：故障注入次数。 */
    public long injectedTotal() {
        Object value = metricsService.snapshot().get("chaosInjectedTotal");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
