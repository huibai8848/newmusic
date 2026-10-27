package com.example.newmusic.module.ops.chaos;

import com.example.newmusic.module.ops.entity.ChaosExperiment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前生效的混沌实验（FR-28-011）。
 *
 * <p>故障注入点读取这里的状态，而不是每次都查库——混沌实验是「按请求概率注入」，
 * 查库会把故障注入变成数据库压力。实验启动/停止时由 {@link ChaosService} 更新。
 *
 * <p><b>爆炸半径就是注入概率</b>（百分比）。例如 blastRadius=10 表示 10% 的请求受影响。
 * 这样「只影响小部分流量」是可执行的，而不是一句口号。
 * 同一时刻只允许一个 RUNNING 实验：两个互相叠加的故障会让结果无法归因。
 */
@Component
public class ChaosState {

    public static final String FAULT_REDIS_LIMITER = "REDIS_LIMITER_UNAVAILABLE";
    public static final String FAULT_AI_TIMEOUT = "AI_TIMEOUT";
    public static final String FAULT_SLOW_DB = "SLOW_DB";

    private final AtomicReference<Active> active = new AtomicReference<>();

    public void start(ChaosExperiment experiment) {
        active.set(new Active(experiment.getId(), experiment.getFaultType(),
                experiment.getBlastRadius() == null ? 0 : experiment.getBlastRadius(),
                System.currentTimeMillis() + Math.max(1, experiment.getDurationSeconds()) * 1000L));
    }

    public void stop() {
        active.set(null);
    }

    public ChaosExperiment snapshot() {
        Active current = active.get();
        if (current == null || current.endAt < System.currentTimeMillis()) {
            return null;
        }
        ChaosExperiment experiment = new ChaosExperiment();
        experiment.setId(current.id);
        experiment.setFaultType(current.faultType);
        experiment.setBlastRadius(current.blastRadius);
        experiment.setStatus(ChaosExperiment.STATUS_RUNNING);
        long remaining = Math.max(0, (current.endAt - System.currentTimeMillis()) / 1000L);
        experiment.setDurationSeconds((int) remaining);
        return experiment;
    }

    public boolean isActive(String faultType) {
        Active current = active.get();
        return current != null && faultType.equals(current.faultType)
                && current.endAt >= System.currentTimeMillis();
    }

    /** 按爆炸半径决定本次请求是否注入故障。 */
    public boolean shouldInject(String faultType) {
        Active current = active.get();
        if (current == null || !faultType.equals(current.faultType)
                || current.endAt < System.currentTimeMillis()) {
            return false;
        }
        if (current.blastRadius <= 0) {
            return false;
        }
        if (current.blastRadius >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < current.blastRadius;
    }

    /** 实验是否已到结束时间（由定时任务读取并落库）。 */
    public Long expiredExperimentId() {
        Active current = active.get();
        if (current != null && current.endAt < System.currentTimeMillis()) {
            return current.id;
        }
        return null;
    }

    private static final class Active {
        private final Long id;
        private final String faultType;
        private final int blastRadius;
        private final long endAt;

        private Active(Long id, String faultType, int blastRadius, long endAt) {
            this.id = id;
            this.faultType = faultType;
            this.blastRadius = blastRadius;
            this.endAt = endAt;
        }
    }
}
