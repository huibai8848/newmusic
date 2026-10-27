package com.example.newmusic.module.ops.chaos;

import com.example.newmusic.module.ops.entity.ChaosExperiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混沌实验状态单测（FR-28-011）。
 *
 * <p>爆炸半径就是注入概率：0 不注入、100 全量注入；故障类型必须匹配才会命中。
 * 实验到点自动失效，不依赖人工停止——这是「忘记关演练」的最后一道保险。
 */
@DisplayName("ChaosState 实验状态")
class ChaosStateTest {

    private ChaosExperiment experiment(String fault, int blastRadius, int durationSeconds) {
        ChaosExperiment experiment = new ChaosExperiment();
        experiment.setId(9001L);
        experiment.setFaultType(fault);
        experiment.setBlastRadius(blastRadius);
        experiment.setDurationSeconds(durationSeconds);
        return experiment;
    }

    @Test
    @DisplayName("启动后按故障类型命中，100% 半径必定注入")
    void activeFaultInjects() {
        ChaosState state = new ChaosState();
        state.start(experiment(ChaosState.FAULT_AI_TIMEOUT, 100, 60));

        assertTrue(state.isActive(ChaosState.FAULT_AI_TIMEOUT));
        assertTrue(state.shouldInject(ChaosState.FAULT_AI_TIMEOUT));
        assertFalse(state.isActive(ChaosState.FAULT_SLOW_DB), "故障类型必须精确匹配");
        assertFalse(state.shouldInject(ChaosState.FAULT_SLOW_DB));

        ChaosExperiment snapshot = state.snapshot();
        assertNotNull(snapshot);
        assertEquals(ChaosExperiment.STATUS_RUNNING, snapshot.getStatus());
        assertEquals(100, snapshot.getBlastRadius());
    }

    @Test
    @DisplayName("半径为 0 时不注入，避免误配成全量")
    void zeroRadiusNeverInjects() {
        ChaosState state = new ChaosState();
        state.start(experiment(ChaosState.FAULT_REDIS_LIMITER, 0, 60));

        assertTrue(state.isActive(ChaosState.FAULT_REDIS_LIMITER));
        assertFalse(state.shouldInject(ChaosState.FAULT_REDIS_LIMITER));
    }

    @Test
    @DisplayName("停止后立即失效")
    void stopClearsExperiment() {
        ChaosState state = new ChaosState();
        state.start(experiment(ChaosState.FAULT_SLOW_DB, 50, 60));
        state.stop();

        assertNull(state.snapshot());
        assertFalse(state.isActive(ChaosState.FAULT_SLOW_DB));
        assertFalse(state.shouldInject(ChaosState.FAULT_SLOW_DB));
        assertNull(state.expiredExperimentId());
    }

    @Test
    @DisplayName("到点后不再注入，并暴露过期实验 ID 供调度落库")
    void expiresAutomatically() throws InterruptedException {
        ChaosState state = new ChaosState();
        state.start(experiment(ChaosState.FAULT_AI_TIMEOUT, 100, 1));

        Thread.sleep(1100L);

        assertNull(state.snapshot(), "过期实验不再作为当前实验返回");
        assertFalse(state.isActive(ChaosState.FAULT_AI_TIMEOUT));
        assertFalse(state.shouldInject(ChaosState.FAULT_AI_TIMEOUT));
        assertEquals(Long.valueOf(9001L), state.expiredExperimentId());
    }
}
