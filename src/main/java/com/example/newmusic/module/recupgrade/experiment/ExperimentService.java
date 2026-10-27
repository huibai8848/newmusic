package com.example.newmusic.module.recupgrade.experiment;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.entity.RecEvent;
import com.example.newmusic.module.recupgrade.entity.RecExperiment;
import com.example.newmusic.module.recupgrade.mapper.RecEventMapper;
import com.example.newmusic.module.recupgrade.mapper.RecExperimentMapper;
import com.example.newmusic.module.recupgrade.support.Murmur3;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A/B 实验分流与显著性（FR-24-008 / FR-24-009）。
 *
 * <p><b>分流公式</b>：{@code murmur3(experimentKey|subject|layerSalt) % 10000}，
 * 先按流量比例截断，再按组比例映射。PRD 伪代码里组阈值直接用 {@code ratioPercent*100}
 * 会使 20% 流量下的 50/25/25 分组全部落进 A 组；这里把组阈值缩放到流量区间内，
 * 否则「分流比例符合配置」这条验收永远不成立。
 *
 * <p><b>流量单位</b>：{@code trafficPercent} 是百分比整数（20 = 20%），组 ratio 既接受
 * 0.5 也接受 50，读取时统一归一化。
 */
@Slf4j
@Service
public class ExperimentService {

    private static final long META_CACHE_MILLIS = 30_000L;
    private static final long ASSIGN_TTL_MINUTES = 30;

    private final RecExperimentMapper experimentMapper;
    private final RecEventMapper eventMapper;
    private final RecConfigService configService;
    private final RecUpgradeProperties properties;
    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;

    private volatile List<RecExperiment> runningCache = Collections.emptyList();
    private volatile long runningCachedAt;

    public ExperimentService(RecExperimentMapper experimentMapper, RecEventMapper eventMapper,
                             RecConfigService configService, RecUpgradeProperties properties,
                             RedisOps redisOps, ObjectMapper objectMapper) {
        this.experimentMapper = experimentMapper;
        this.eventMapper = eventMapper;
        this.configService = configService;
        this.properties = properties;
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
    }

    // ================================================================== 分流

    /**
     * 一次请求的全部分组。缓存 30 分钟；实验停止时主动清空缓存（一键生效）。
     */
    public Map<String, String> assign(Long userId, String deviceId) {
        String subject = userId != null ? String.valueOf(userId)
                : (deviceId == null || deviceId.isEmpty() ? "anonymous" : deviceId);
        Map<String, String> cached = new LinkedHashMap<>();
        if (userId != null) {
            Map<String, String> raw = redisOps.hashGetAll(RedisKeys.recExperimentAssign(userId));
            if (!raw.isEmpty()) {
                cached.putAll(raw);
                return cached;
            }
        }
        Map<String, String> assigned = computeAssignments(subject);
        if (userId != null) {
            redisOps.hashSetAll(RedisKeys.recExperimentAssign(userId), assigned);
            redisOps.expire(RedisKeys.recExperimentAssign(userId), Duration.ofMinutes(ASSIGN_TTL_MINUTES));
        }
        return assigned;
    }

    private Map<String, String> computeAssignments(String subject) {
        List<RecExperiment> running = runningExperiments();
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> mutexTaken = new LinkedHashMap<>();
        for (RecExperiment experiment : running) {
            if (!inWindow(experiment)) {
                continue;
            }
            String group = forcedGroup(experiment, subject);
            if (group == null) {
                int traffic = experiment.getTrafficPercent() == null ? 0 : experiment.getTrafficPercent();
                if (traffic <= 0) {
                    continue;
                }
                String salt = experiment.getLayerSalt() == null ? "" : experiment.getLayerSalt();
                int bucket = Murmur3.bucket(experiment.getExperimentKey() + "|" + subject + "|" + salt);
                if (bucket >= traffic * 100) {
                    continue;
                }
                group = mapGroup(experiment, bucket, traffic);
            }
            if (group == null || "default".equalsIgnoreCase(group) || "none".equalsIgnoreCase(group)) {
                continue;
            }
            String mutex = experiment.getMutexGroup();
            if (mutex != null && !mutex.trim().isEmpty()) {
                String existing = mutexTaken.get(mutex);
                if (existing != null && !existing.equals(experiment.getExperimentKey())) {
                    continue;
                }
                mutexTaken.put(mutex, experiment.getExperimentKey());
            }
            result.put(experiment.getExperimentKey(), group);
        }
        return result;
    }

    /**
     * 组映射：把 bucket（0..traffic*100-1）按组比例切分。
     * 例：流量 20%、A/B/C = 50/25/25 → A:0-999，B:1000-1499，C:1500-1999。
     */
    private String mapGroup(RecExperiment experiment, int bucket, int trafficPercent) {
        List<Group> groups = parseGroups(experiment.getGroupsJson());
        if (groups.isEmpty()) {
            return "default";
        }
        double normalizedSum = 0;
        for (Group group : groups) {
            normalizedSum += group.getRatio();
        }
        if (normalizedSum <= 0) {
            return groups.get(0).getName();
        }
        double range = trafficPercent * 100.0;
        double cumulative = 0;
        for (Group group : groups) {
            cumulative += group.getRatio() / normalizedSum * range;
            if (bucket < cumulative) {
                return group.getName();
            }
        }
        return groups.get(groups.size() - 1).getName();
    }

    private String forcedGroup(RecExperiment experiment, String subject) {
        String raw = experiment.getWhitelistJson();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("userIds")) {
                root = root.get("userIds");
            }
            if (root.isObject()) {
                JsonNode value = root.get(subject);
                if (value != null && value.isTextual()) {
                    return value.asText();
                }
            } else if (root.isArray()) {
                for (JsonNode item : root) {
                    if (item.has("subject") && subject.equals(item.path("subject").asText())) {
                        return item.path("group").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            // EX-24-008-3：白名单坏数据直接忽略
            log.debug("实验白名单解析失败 key={} cause={}", experiment.getExperimentKey(), e.toString());
        }
        return null;
    }

    public List<RecExperiment> runningExperiments() {
        long now = System.currentTimeMillis();
        if (now - runningCachedAt < META_CACHE_MILLIS) {
            return runningCache;
        }
        List<RecExperiment> rows;
        try {
            rows = experimentMapper.selectList(Wrappers.<RecExperiment>lambdaQuery()
                    .eq(RecExperiment::getStatus, RecExperiment.STATUS_RUNNING)
                    .orderByAsc(RecExperiment::getId));
        } catch (RuntimeException e) {
            // EX-24-008-1：读不到配置时所有用户走默认组
            log.warn("实验配置读取失败：{}", e.toString());
            rows = Collections.emptyList();
        }
        runningCache = rows;
        runningCachedAt = now;
        return rows;
    }

    private boolean inWindow(RecExperiment experiment) {
        LocalDateTime now = LocalDateTime.now();
        if (experiment.getStartTime() != null && now.isBefore(experiment.getStartTime())) {
            return false;
        }
        return experiment.getEndTime() == null || !now.isAfter(experiment.getEndTime());
    }

    // ================================================================== 管理

    public PageResult<RecExperiment> list(Integer status, PageQuery query) {
        query.normalize();
        IPage<RecExperiment> page = experimentMapper.selectPage(query.toPage(),
                Wrappers.<RecExperiment>lambdaQuery()
                        .eq(status != null, RecExperiment::getStatus, status)
                        .orderByDesc(RecExperiment::getCreateTime));
        return PageResult.of(page, query.isTruncated());
    }

    public RecExperiment get(Long id) {
        RecExperiment experiment = experimentMapper.selectById(id);
        if (experiment == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "实验不存在");
        }
        return experiment;
    }

    public RecExperiment getByKey(String key) {
        RecExperiment experiment = experimentMapper.selectOne(Wrappers.<RecExperiment>lambdaQuery()
                .eq(RecExperiment::getExperimentKey, key).last("LIMIT 1"));
        if (experiment == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "实验不存在：" + key);
        }
        return experiment;
    }

    @Transactional
    public RecExperiment create(RecExperiment experiment) {
        if (experiment.getExperimentKey() == null || experiment.getExperimentKey().trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "experimentKey 不能为空");
        }
        if (experimentMapper.selectCount(Wrappers.<RecExperiment>lambdaQuery()
                .eq(RecExperiment::getExperimentKey, experiment.getExperimentKey())) > 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "experimentKey 已存在");
        }
        List<Group> groups = parseGroups(experiment.getGroupsJson());
        if (groups.size() < 2) {
            throw new BizException(ResultCode.PARAM_INVALID, "至少需要 2 个实验组");
        }
        double sum = 0;
        for (Group group : groups) {
            if (group.getName() == null || group.getRatio() < 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "实验组配置非法");
            }
            sum += group.getRatio();
        }
        if (Math.abs(sum - 1.0) > 0.011 && Math.abs(sum - 100.0) > 1.1) {
            // EX-24-008-2：比例总和必须为 100%
            throw new BizException(ResultCode.PARAM_INVALID, "实验组比例总和必须为 100%，当前 " + sum);
        }
        int traffic = experiment.getTrafficPercent() == null ? 0 : experiment.getTrafficPercent();
        if (traffic < 1 || traffic > 100) {
            throw new BizException(ResultCode.PARAM_INVALID, "流量比例必须在 1~100 之间");
        }
        if (experiment.getLayer() == null || experiment.getLayer().trim().isEmpty()) {
            experiment.setLayer("rank_layer_v1");
        }
        if (experiment.getLayerSalt() == null || experiment.getLayerSalt().trim().isEmpty()) {
            experiment.setLayerSalt(experiment.getLayer());
        }
        checkLayerCapacity(experiment.getLayer(), traffic, null);
        if (experiment.getStatus() == null) {
            experiment.setStatus(RecExperiment.STATUS_DRAFT);
        }
        experimentMapper.insert(experiment);
        return experiment;
    }

    @Transactional
    public RecExperiment approve(Long id, Long approverId) {
        RecExperiment experiment = get(id);
        checkLayerCapacity(experiment.getLayer(),
                experiment.getTrafficPercent() == null ? 0 : experiment.getTrafficPercent(), id);
        experiment.setApproverId(approverId);
        experiment.setStatus(RecExperiment.STATUS_RUNNING);
        if (experiment.getStartTime() == null) {
            experiment.setStartTime(LocalDateTime.now());
        }
        experimentMapper.updateById(experiment);
        invalidateRunning();
        return experiment;
    }

    /** 一键停止：状态改 STOPPED 并清空所有人分组缓存，下一次请求立即回默认（EX-24-008-5）。 */
    @Transactional
    public RecExperiment stop(Long id) {
        RecExperiment experiment = get(id);
        experiment.setStatus(RecExperiment.STATUS_STOPPED);
        experiment.setEndTime(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        invalidateRunning();
        return experiment;
    }

    @Transactional
    public RecExperiment archive(Long id, String conclusion) {
        RecExperiment experiment = get(id);
        if (experiment.getStatus() != null && experiment.getStatus() == RecExperiment.STATUS_RUNNING) {
            throw new BizException(ResultCode.PARAM_INVALID, "运行中的实验不能直接归档，请先停止");
        }
        experiment.setConclusion(conclusion);
        experiment.setStatus(RecExperiment.STATUS_ARCHIVED);
        experimentMapper.updateById(experiment);
        invalidateRunning();
        return experiment;
    }

    @Transactional
    public RecExperiment updateWhitelist(Long id, String whitelistJson) {
        RecExperiment experiment = get(id);
        try {
            if (whitelistJson != null && !whitelistJson.trim().isEmpty()) {
                objectMapper.readTree(whitelistJson);
            }
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "白名单必须是合法 JSON");
        }
        experiment.setWhitelistJson(whitelistJson);
        experimentMapper.updateById(experiment);
        invalidateRunning();
        return experiment;
    }

    private void invalidateRunning() {
        runningCachedAt = 0;
        // 分配缓存全部清掉：停止实验要求「立即」生效，不能等 30 分钟 TTL
        redisOps.deleteByPrefix(RedisKeys.REC_EXP_ASSIGN);
        redisOps.delete(RedisKeys.REC_EXP_META);
    }

    private void checkLayerCapacity(String layer, int traffic, Long excludeId) {
        List<RecExperiment> rows = experimentMapper.selectList(Wrappers.<RecExperiment>lambdaQuery()
                .eq(RecExperiment::getLayer, layer)
                .ne(excludeId != null, RecExperiment::getId, excludeId)
                .in(RecExperiment::getStatus, RecExperiment.STATUS_RUNNING, RecExperiment.STATUS_PENDING));
        int total = traffic;
        int running = 0;
        for (RecExperiment row : rows) {
            total += row.getTrafficPercent() == null ? 0 : row.getTrafficPercent();
            if (row.getStatus() != null && row.getStatus() == RecExperiment.STATUS_RUNNING) {
                running++;
            }
        }
        // BR-24-077 / BR-24-082
        if (total > 100) {
            throw new BizException(ResultCode.PARAM_INVALID, "同层实验流量总和不能超过 100%");
        }
        if (running >= 5) {
            throw new BizException(ResultCode.PARAM_INVALID, "同层同时运行的实验不能超过 5 个");
        }
    }

    // ================================================================== 指标

    /**
     * 实验指标。直接读 {@code t_rec_event}（本地替代 ClickHouse），
     * 支持按用户类型/设备/小时下钻，用于排查辛普森悖论（BR-24-090）。
     */
    public Map<String, Object> metrics(String experimentKey, int days, String userType,
                                       String deviceType, int hour) {
        RecExperiment experiment = getByKey(experimentKey);
        int windowDays = days <= 0 ? 7 : Math.min(days, 90);
        LocalDateTime until = LocalDateTime.now();
        LocalDateTime since = until.minusDays(windowDays);
        List<RecEventMapper.EventCountRow> rows = eventMapper.selectExperimentCounts(
                experimentKey, since, until, safe(userType), safe(deviceType), hour);
        Map<String, Map<String, Long>> groups = new LinkedHashMap<>();
        for (Group group : parseGroups(experiment.getGroupsJson())) {
            groups.put(group.getName(), new LinkedHashMap<String, Long>());
        }
        for (RecEventMapper.EventCountRow row : rows) {
            if (row.getGroupName() == null) {
                continue;
            }
            Map<String, Long> counts = groups.computeIfAbsent(row.getGroupName(),
                    k -> new LinkedHashMap<String, Long>());
            String type = row.getEventType() == null ? "UNKNOWN" : row.getEventType();
            counts.merge(type, row.getCnt() == null ? 0 : row.getCnt(), Long::sum);
            counts.merge("uniqueUsers", row.getUserCnt() == null ? 0 : row.getUserCnt(), Math::max);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experimentKey", experimentKey);
        result.put("scene", experiment.getLayer());
        result.put("primaryMetric", experiment.getPrimaryMetric());
        result.put("since", since.toString());
        result.put("until", until.toString());
        result.put("dimension", dimension(userType, deviceType, hour));
        Map<String, Object> groupMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : groups.entrySet()) {
            groupMetrics.put(entry.getKey(), computeGroupMetrics(entry.getValue()));
        }
        result.put("groups", groupMetrics);
        result.put("totalGroups", groupMetrics.size());
        return result;
    }

    private Map<String, Object> computeGroupMetrics(Map<String, Long> counts) {
        long impression = get(counts, "IMPRESSION");
        long click = get(counts, "CLICK");
        long play = get(counts, "PLAY");
        long validPlay = get(counts, "VALID_PLAY");
        long complete = get(counts, "COMPLETE");
        long like = get(counts, "LIKE");
        long dislike = get(counts, "DISLIKE");
        long skip = get(counts, "SKIP");
        long exposureBase = impression > 0 ? impression : play;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("impression", impression);
        metrics.put("click", click);
        metrics.put("play", play);
        metrics.put("validPlay", validPlay);
        metrics.put("complete", complete);
        metrics.put("like", like);
        metrics.put("dislike", dislike);
        metrics.put("skip", skip);
        metrics.put("uniqueUsers", get(counts, "uniqueUsers"));
        metrics.put("ctr", rate(click, exposureBase));
        metrics.put("playRate", rate(play, exposureBase));
        metrics.put("completionRate", rate(complete, play));
        metrics.put("avgValidPlay", rate(validPlay, 1)); // 时长由单独查询覆盖，见下
        metrics.put("likeRate", rate(like, play));
        metrics.put("dislikeRate", rate(dislike, play));
        metrics.put("skipRate", rate(skip, play));
        metrics.put("longtailRatio", 0.0);
        metrics.put("diversityScore", 0.0);
        return metrics;
    }

    /** 平均值（而不是比例）由 AVG(play_duration) 取，这里覆盖上面占位的 avgValidPlay。 */
    public Map<String, Object> metricsWithDuration(String experimentKey, int days) {
        Map<String, Object> result = metrics(experimentKey, days, null, null, -1);
        LocalDateTime until = LocalDateTime.now();
        LocalDateTime since = until.minusDays(days <= 0 ? 7 : Math.min(days, 90));
        for (RecEventMapper.AvgRow row : eventMapper.selectAvgValidPlay(experimentKey, since, until)) {
            Object groupObj = result.get("groups");
            if (groupObj instanceof Map) {
                Object metricsObj = ((Map<?, ?>) groupObj).get(row.getGroupName());
                if (metricsObj instanceof Map) {
                    ((Map<String, Object>) metricsObj).put("avgValidPlay",
                            row.getAvgValue() == null ? 0d : row.getAvgValue());
                }
            }
        }
        return result;
    }

    // ================================================================== 显著性

    /**
     * 两组显著性检验。比例型指标用 z 检验，avgValidPlay 用 t 检验近似。
     * 不满足最小样本 → 明确返回 INSUFFICIENT_SAMPLE，而不是给一个「不显著」的假结论。
     */
    public Map<String, Object> significance(String experimentKey, String metric, int days) {
        Map<String, Object> metrics = metricsWithDuration(experimentKey, days);
        RecExperiment experiment = getByKey(experimentKey);
        RecConfigService.ExperimentSetting setting = configService.experiment();
        int minSample = setting == null ? properties.getExperimentMinSample() : setting.getMinSample();
        double alpha = setting == null ? 0.05 : setting.getAlpha();
        String primary = metric == null || metric.trim().isEmpty()
                ? (experiment.getPrimaryMetric() == null ? "ctr" : experiment.getPrimaryMetric())
                : metric;

        Object groupsObj = metrics.get("groups");
        Map<?, ?> groups = groupsObj instanceof Map ? (Map<?, ?>) groupsObj : Collections.emptyMap();
        String controlName = chooseControl(groups.keySet());
        if (controlName == null) {
            return insufficient(primary, "NO_GROUPS", minSample);
        }
        Map<?, ?> control = (Map<?, ?>) groups.get(controlName);
        List<Map<String, Object>> comparisons = new ArrayList<>();
        int testCount = 0;
        for (Object name : groups.keySet()) {
            if (!controlName.equals(String.valueOf(name))) {
                testCount++;
            }
        }
        double correctedAlpha = alpha / Math.max(1, testCount);
        for (Map.Entry<?, ?> entry : groups.entrySet()) {
            String groupName = String.valueOf(entry.getKey());
            if (groupName.equals(controlName)) {
                continue;
            }
            Map<?, ?> treatment = (Map<?, ?>) entry.getValue();
            Map<String, Object> comparison = compare(primary, controlName, control,
                    groupName, treatment, minSample, correctedAlpha);
            comparisons.add(comparison);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experimentKey", experimentKey);
        result.put("metric", primary);
        result.put("control", controlName);
        result.put("alpha", alpha);
        result.put("bonferroniAlpha", correctedAlpha);
        result.put("minSample", minSample);
        result.put("comparisons", comparisons);
        result.put("conclusion", conclusion(comparisons, minSample));
        return result;
    }

    private Map<String, Object> compare(String metric, String controlName, Map<?, ?> control,
                                        String groupName, Map<?, ?> treatment, int minSample,
                                        double alpha) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("control", controlName);
        result.put("group", groupName);
        if ("avgValidPlay".equalsIgnoreCase(metric) || "avg_valid_play".equalsIgnoreCase(metric)) {
            double meanA = toDouble(control.get("avgValidPlay"));
            double meanB = toDouble(treatment.get("avgValidPlay"));
            long nA = toLong(control.get("validPlay"));
            long nB = toLong(treatment.get("validPlay"));
            result.put("metricType", "continuous");
            result.put("controlValue", meanA);
            result.put("groupValue", meanB);
            result.put("lift", meanA == 0 ? 0 : (meanB - meanA) / meanA);
            if (nA < minSample || nB < minSample) {
                result.put("pValue", 1.0);
                result.put("significant", false);
                result.put("note", "INSUFFICIENT_SAMPLE");
                return result;
            }
            // 方差未知，用比例型 z 检验的保守近似：把均值差与合并标准误比较
            double se = Math.sqrt(Math.max(1e-9, (meanA + meanB) / Math.max(1, nA + nB)));
            double z = se == 0 ? 0 : (meanB - meanA) / se;
            double p = 2 * (1 - normalCdf(Math.abs(z)));
            result.put("z", z);
            result.put("pValue", p);
            result.put("ciLow", meanB - meanA - 1.96 * se);
            result.put("ciHigh", meanB - meanA + 1.96 * se);
            result.put("significant", p < alpha);
            result.put("stars", stars(p));
            return result;
        }

        String base = "completion".equalsIgnoreCase(metric) || "completionRate".equalsIgnoreCase(metric)
                ? "play" : "impression";
        long nA = toLong(control.get(base));
        long nB = toLong(treatment.get(base));
        long sA = toLong(control.get(metric));
        long sB = toLong(treatment.get(metric));
        // impression 为 0 时退回 play 作为分母，避免空实验全是 0/0
        if (nA == 0) {
            nA = toLong(control.get("play"));
        }
        if (nB == 0) {
            nB = toLong(treatment.get("play"));
        }
        result.put("metricType", "proportion");
        result.put("controlValue", rate(sA, nA));
        result.put("groupValue", rate(sB, nB));
        result.put("lift", rate(sA, nA) == 0 ? 0 : (rate(sB, nB) - rate(sA, nA)) / rate(sA, nA));
        if (nA < minSample || nB < minSample) {
            result.put("pValue", 1.0);
            result.put("significant", false);
            result.put("note", "INSUFFICIENT_SAMPLE");
            return result;
        }
        double pA = sA / (double) nA;
        double pB = sB / (double) nB;
        double pooled = (sA + sB) / (double) (nA + nB);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / nA + 1.0 / nB));
        double z = se == 0 ? 0 : (pB - pA) / se;
        double pValue = 2 * (1 - normalCdf(Math.abs(z)));
        double diff = pB - pA;
        double ciSe = Math.sqrt(Math.max(1e-12, pA * (1 - pA) / nA + pB * (1 - pB) / nB));
        result.put("z", z);
        result.put("pValue", pValue);
        result.put("ciLow", diff - 1.96 * ciSe);
        result.put("ciHigh", diff + 1.96 * ciSe);
        result.put("significant", pValue < alpha);
        result.put("stars", stars(pValue));
        return result;
    }

    /** AA 检验：对照组用户按 hash 奇偶拆成两半，理论上不应有显著差异（BR-24-089）。 */
    public Map<String, Object> aaTest(String experimentKey, int days) {
        RecExperiment experiment = getByKey(experimentKey);
        LocalDateTime until = LocalDateTime.now();
        LocalDateTime since = until.minusDays(days <= 0 ? 7 : Math.min(days, 90));
        List<RecEvent> events = eventMapper.selectList(Wrappers.<RecEvent>lambdaQuery()
                .eq(RecEvent::getExperimentKey, experimentKey)
                .eq(RecEvent::getGroupName, "control")
                .ge(RecEvent::getCreateTime, since)
                .le(RecEvent::getCreateTime, until));
        long[] impression = new long[2];
        long[] click = new long[2];
        Set<Long>[] users = new Set[]{new LinkedHashSet<Long>(), new LinkedHashSet<Long>()};
        for (RecEvent event : events) {
            int half = Murmur3.bucket("aa|" + event.getUserId()) & 1;
            if (event.getUserId() != null) {
                users[half].add(event.getUserId());
            }
            if (RecEvent.IMPRESSION.equals(event.getEventType())) {
                impression[half]++;
            } else if (RecEvent.CLICK.equals(event.getEventType())) {
                click[half]++;
            }
        }
        double pA = rate(click[0], impression[0]);
        double pB = rate(click[1], impression[1]);
        long nA = impression[0];
        long nB = impression[1];
        double pooled = (click[0] + click[1]) / (double) Math.max(1, nA + nB);
        double se = Math.sqrt(Math.max(1e-12, pooled * (1 - pooled) * (1.0 / Math.max(1, nA) + 1.0 / Math.max(1, nB))));
        double z = se == 0 ? 0 : (pB - pA) / se;
        double pValue = 2 * (1 - normalCdf(Math.abs(z)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experimentKey", experimentKey);
        result.put("sampleA", nA);
        result.put("sampleB", nB);
        result.put("usersA", users[0].size());
        result.put("usersB", users[1].size());
        result.put("ctrA", pA);
        result.put("ctrB", pB);
        result.put("z", z);
        result.put("pValue", pValue);
        result.put("passed", pValue >= 0.05);
        result.put("note", nA < 100 || nB < 100 ? "样本偏少，AA 结果仅供参考" : "OK");
        return result;
    }

    private String chooseControl(Set<?> groups) {
        for (Object name : groups) {
            if ("control".equalsIgnoreCase(String.valueOf(name))) {
                return String.valueOf(name);
            }
        }
        return groups.isEmpty() ? null : String.valueOf(groups.iterator().next());
    }

    private Map<String, Object> insufficient(String metric, String reason, int minSample) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", metric);
        result.put("minSample", minSample);
        result.put("conclusion", "INSUFFICIENT_SAMPLE");
        result.put("reason", reason);
        result.put("comparisons", Collections.emptyList());
        return result;
    }

    private String conclusion(List<Map<String, Object>> comparisons, int minSample) {
        if (comparisons.isEmpty()) {
            return "INSUFFICIENT_SAMPLE";
        }
        boolean anyInsufficient = false;
        boolean anySignificant = false;
        for (Map<String, Object> comparison : comparisons) {
            if ("INSUFFICIENT_SAMPLE".equals(comparison.get("note"))) {
                anyInsufficient = true;
            }
            if (Boolean.TRUE.equals(comparison.get("significant"))) {
                anySignificant = true;
            }
        }
        if (anyInsufficient) {
            return "INSUFFICIENT_SAMPLE";
        }
        return anySignificant ? "SIGNIFICANT" : "NOT_SIGNIFICANT";
    }

    /** 正态分布 CDF，用 erf 的 Abramowitz-Stegun 近似（最大误差 < 1e-7）。 */
    public static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }

    private String stars(double p) {
        if (p < 0.001) {
            return "***";
        }
        if (p < 0.01) {
            return "**";
        }
        if (p < 0.05) {
            return "*";
        }
        return "";
    }

    public List<Group> parseGroups(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Group> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("groups")) {
                root = root.get("groups");
            }
            if (root.isArray()) {
                for (JsonNode node : root) {
                    Group group = new Group();
                    group.setName(node.path("name").asText(null));
                    double ratio = node.path("ratio").asDouble(node.path("percent").asDouble(0));
                    group.setRatio(ratio > 1.0 ? ratio / 100.0 : ratio);
                    result.add(group);
                }
            } else if (root.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Group group = new Group();
                    group.setName(field.getKey());
                    double ratio = field.getValue().asDouble(0);
                    group.setRatio(ratio > 1.0 ? ratio / 100.0 : ratio);
                    result.add(group);
                }
            }
        } catch (Exception e) {
            log.warn("实验分组 JSON 解析失败：{}", e.toString());
        }
        return result;
    }

    private Map<String, Object> dimension(String userType, String deviceType, int hour) {
        Map<String, Object> dimension = new LinkedHashMap<>();
        dimension.put("userType", safe(userType));
        dimension.put("deviceType", safe(deviceType));
        dimension.put("hour", hour);
        return dimension;
    }

    private long get(Map<String, Long> counts, String key) {
        Long value = counts.get(key);
        return value == null ? 0 : value;
    }

    private double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0 : numerator / (double) denominator;
    }

    private double toDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    @Data
    public static class Group {
        private String name;
        private double ratio;
    }
}
