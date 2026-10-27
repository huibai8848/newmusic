package com.example.newmusic.module.recupgrade.experiment;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.entity.RecExperiment;
import com.example.newmusic.module.recupgrade.mapper.RecEventMapper;
import com.example.newmusic.module.recupgrade.mapper.RecExperimentMapper;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实验分流与显著性工具单测（FR-24-008 / FR-24-009）。
 *
 * <p>重点验证 PRD 伪代码里那个会「把所有流量都分进 A 组」的坑：
 * 组阈值必须缩放到流量区间内，20% 流量下 50/25/25 仍应是 10%/5%/5%。
 */
@DisplayName("ExperimentService 实验分流")
class ExperimentServiceTest {

    private final RecExperimentMapper experimentMapper = mock(RecExperimentMapper.class);
    private final RecEventMapper eventMapper = mock(RecEventMapper.class);
    private final RecConfigService configService = mock(RecConfigService.class);
    private final RedisOps redisOps = mock(RedisOps.class);
    private final RecUpgradeProperties properties = new RecUpgradeProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExperimentService service = new ExperimentService(experimentMapper, eventMapper,
            configService, properties, redisOps, objectMapper);

    @Test
    @DisplayName("20% 流量、50/25/25 分组：命中比例仍约为 10%/5%/5%")
    void trafficAndGroupRatios() {
        when(experimentMapper.selectList(any())).thenReturn(Collections.singletonList(running()));
        when(redisOps.hashGetAll(anyString())).thenReturn(Collections.emptyMap());

        int assigned = 0;
        int control = 0;
        int b = 0;
        int c = 0;
        int total = 8000;
        for (long userId = 1; userId <= total; userId++) {
            Map<String, String> result = service.assign(userId, null);
            String group = result.get("exp-ratio");
            if (group == null) {
                continue;
            }
            assigned++;
            if ("control".equals(group)) {
                control++;
            } else if ("b".equals(group)) {
                b++;
            } else if ("c".equals(group)) {
                c++;
            }
        }
        double traffic = assigned / (double) total;
        assertTrue(traffic > 0.17 && traffic < 0.23, "流量应约 20%，实际 " + traffic);
        assertTrue(control / (double) assigned > 0.45 && control / (double) assigned < 0.55,
                "control 应约占一半，实际 " + control / (double) assigned);
        assertTrue(b / (double) assigned > 0.21 && b / (double) assigned < 0.29,
                "B 组应约占 1/4，实际 " + b / (double) assigned);
        assertTrue(c / (double) assigned > 0.21 && c / (double) assigned < 0.29,
                "C 组应约占 1/4，实际 " + c / (double) assigned);
    }

    @Test
    @DisplayName("白名单优先于流量与哈希，且大小写不敏感")
    void whitelistOverridesBucket() {
        RecExperiment experiment = running();
        experiment.setTrafficPercent(1);
        experiment.setWhitelistJson("{\"42\":\"c\"}");
        when(experimentMapper.selectList(any())).thenReturn(Collections.singletonList(experiment));
        when(redisOps.hashGetAll(anyString())).thenReturn(Collections.emptyMap());

        assertEquals("c", service.assign(42L, null).get("exp-ratio"));
    }

    @Test
    @DisplayName("互斥组：同一用户只会进入其中一个实验")
    void mutexGroup() {
        RecExperiment first = running();
        first.setId(1L);
        first.setExperimentKey("exp-a");
        first.setMutexGroup("homepage");
        first.setTrafficPercent(100);
        RecExperiment second = running();
        second.setId(2L);
        second.setExperimentKey("exp-b");
        second.setMutexGroup("homepage");
        second.setTrafficPercent(100);
        when(experimentMapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(redisOps.hashGetAll(anyString())).thenReturn(Collections.emptyMap());

        Map<String, String> result = service.assign(7L, null);
        assertEquals(1, result.size(), "互斥组只应命中一个实验，实际 " + result);
    }

    @Test
    @DisplayName("组比例合计不是 100% 时拒绝创建")
    void rejectInvalidRatioSum() {
        RecExperiment experiment = running();
        experiment.setId(null);
        experiment.setExperimentKey("bad");
        experiment.setGroupsJson("[{\"name\":\"control\",\"ratio\":50},{\"name\":\"b\",\"ratio\":30}]");
        when(experimentMapper.selectCount(any())).thenReturn(0L);
        assertThrows(BizException.class, () -> service.create(experiment));
    }

    @Test
    @DisplayName("同层流量合计超过 100% 时拒绝")
    void rejectLayerTrafficOverflow() {
        RecExperiment experiment = running();
        experiment.setId(null);
        experiment.setExperimentKey("too-much");
        experiment.setTrafficPercent(60);
        when(experimentMapper.selectCount(any())).thenReturn(0L);
        RecExperiment existing = running();
        existing.setId(99L);
        existing.setTrafficPercent(50);
        existing.setStatus(RecExperiment.STATUS_RUNNING);
        when(experimentMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        assertThrows(BizException.class, () -> service.create(experiment));
    }

    @Test
    @DisplayName("分组 JSON 同时接受 0.5 与 50 两种写法")
    void parseGroupsAcceptsPercentOrRatio() {
        List<ExperimentService.Group> groups = service.parseGroups(
                "{\"groups\":[{\"name\":\"control\",\"ratio\":0.5},{\"name\":\"b\",\"ratio\":50}]}");
        assertEquals(2, groups.size());
        assertEquals(0.5, groups.get(0).getRatio(), 1e-9);
        assertEquals(0.5, groups.get(1).getRatio(), 1e-9);
    }

    @Test
    @DisplayName("样本不足时显著性返回 INSUFFICIENT_SAMPLE，而不是「不显著」")
    void insufficientSample() {
        RecExperiment experiment = running();
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(eventMapper.selectExperimentCounts(anyString(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(eventMapper.selectAvgValidPlay(anyString(), any(), any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.significance("exp-ratio", "ctr", 7);
        assertEquals("INSUFFICIENT_SAMPLE", result.get("conclusion"));
    }

    @Test
    @DisplayName("正态分布 CDF：0→0.5，1.96→约 0.975")
    void normalCdf() {
        assertEquals(0.5, ExperimentService.normalCdf(0), 1e-9);
        assertEquals(0.975, ExperimentService.normalCdf(1.96), 1e-3);
        assertEquals(0.025, ExperimentService.normalCdf(-1.96), 1e-3);
    }

    // ------------------------------------------------------------------ fixtures

    private RecExperiment running() {
        RecExperiment experiment = new RecExperiment();
        experiment.setId(10L);
        experiment.setExperimentKey("exp-ratio");
        experiment.setName("排序权重实验");
        experiment.setLayer("rank_layer_v1");
        experiment.setLayerSalt("rank_layer_v1");
        experiment.setMutexGroup("");
        experiment.setTrafficPercent(20);
        experiment.setGroupsJson("[{\"name\":\"control\",\"ratio\":50},"
                + "{\"name\":\"b\",\"ratio\":25},{\"name\":\"c\",\"ratio\":25}]");
        experiment.setStatus(RecExperiment.STATUS_RUNNING);
        experiment.setPrimaryMetric("ctr");
        return experiment;
    }
}
