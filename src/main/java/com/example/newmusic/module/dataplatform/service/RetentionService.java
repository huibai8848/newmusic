package com.example.newmusic.module.dataplatform.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.mapper.AnalyticsQueryMapper;
import com.example.newmusic.module.dataplatform.model.RetentionDayRow;
import com.example.newmusic.module.dataplatform.model.RetentionFactRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 留存分析（FR-26-007）。
 *
 * <p>三种口径：新增（按 t_user 注册日期）、活跃（按当日有任意埋点）、播放（按当日有有效播放）。
 * 留存一律按自然日计算（BR-26-067）；新增/活跃的「回访」= 当日有任意埋点，
 * 播放的「回访」= 当日有有效播放（BR-26-068 的默认口径）。
 *
 * <p>分群用 cohort 当日的首日行为划分（社交 / 音乐房 / AI / 重度 / 轻度），
 * 每个分群都带样本量与 Wilson 95% 置信区间（BR-26-073）——样本只有几十人时，
 * 两个分群的几个百分点差异很可能只是噪声，不给区间会误导运营。
 */
@Slf4j
@Service
public class RetentionService {

    private static final int[] DEFAULT_DAYS = {1, 2, 3, 7, 14, 30};
    private static final double Z95 = 1.96d;
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("social", "有社交行为");
        GROUPS.put("room", "使用过音乐房");
        GROUPS.put("ai", "使用过 AI");
        GROUPS.put("heavy", "首日播放 ≥ 10 首");
        GROUPS.put("light", "首日播放 ≤ 2 首");
    }

    private final AnalyticsQueryMapper analyticsMapper;
    private final DataPlatformProperties properties;

    public RetentionService(AnalyticsQueryMapper analyticsMapper, DataPlatformProperties properties) {
        this.analyticsMapper = analyticsMapper;
        this.properties = properties;
    }

    public Map<String, Object> analysis(String type, String start, String end, String group) {
        String retentionType = type == null || type.trim().isEmpty() ? "new" : type.trim();
        if (!Arrays.asList("new", "active", "play").contains(retentionType)) {
            throw new BizException(ResultCode.PARAM_INVALID, "留存类型只支持 new/active/play");
        }
        String groupKey = group == null || group.trim().isEmpty() || "all".equalsIgnoreCase(group.trim())
                ? null : group.trim();
        if (groupKey != null && !GROUPS.containsKey(groupKey)) {
            throw new BizException(ResultCode.PARAM_INVALID, "分群只支持 social/room/ai/heavy/light");
        }

        LocalDate today = LocalDate.now();
        LocalDate rangeEnd = parseDate(end, today.minusDays(1));
        LocalDate rangeStart = parseDate(start, rangeEnd.minusDays(13));
        if (rangeStart.isAfter(rangeEnd)) {
            LocalDate tmp = rangeStart;
            rangeStart = rangeEnd;
            rangeEnd = tmp;
        }
        int maxDays = Math.min(properties.getRetentionDays(), 90);
        if (ChronoUnit.DAYS.between(rangeStart, rangeEnd) > maxDays) {
            throw new BizException(ResultCode.PARAM_INVALID, "留存查询区间不能超过 " + maxDays + " 天");
        }
        LocalDate horizon = rangeEnd.plusDays(30);
        if (horizon.isAfter(today)) {
            horizon = today;
        }

        int limit = Math.max(10000, properties.getRetentionMaxRows());
        List<RetentionFactRow> facts = analyticsMapper.retentionFacts(rangeStart, horizon, limit);
        boolean truncated = facts.size() >= limit;

        Map<Long, Map<LocalDate, RetentionFactRow>> factMap = new HashMap<>();
        for (RetentionFactRow fact : facts) {
            if (fact.getUserId() == null || fact.getDt() == null) {
                continue;
            }
            factMap.computeIfAbsent(fact.getUserId(), k -> new HashMap<>()).put(fact.getDt(), fact);
        }

        Map<Long, Set<LocalDate>> activityDays = new HashMap<>();
        for (Map.Entry<Long, Map<LocalDate, RetentionFactRow>> entry : factMap.entrySet()) {
            activityDays.put(entry.getKey(), new HashSet<>(entry.getValue().keySet()));
        }

        Map<Long, Set<LocalDate>> playDays = new HashMap<>();
        if ("play".equals(retentionType)) {
            for (RetentionDayRow row : analyticsMapper.validPlayDays(rangeStart, horizon,
                    properties.getValidPlaySeconds(), limit)) {
                if (row.getUserId() != null && row.getDt() != null) {
                    playDays.computeIfAbsent(row.getUserId(), k -> new HashSet<>()).add(row.getDt());
                }
            }
        }

        Map<LocalDate, Set<Long>> cohorts = buildCohorts(retentionType, rangeStart, rangeEnd, facts, playDays);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Integer, long[]> curve = new LinkedHashMap<>();
        for (int day : DEFAULT_DAYS) {
            curve.put(day, new long[]{0L, 0L});
        }
        List<LocalDate> cohortDates = new ArrayList<>(cohorts.keySet());
        cohortDates.sort(Comparator.naturalOrder());

        for (LocalDate cohortDate : cohortDates) {
            Set<Long> users = cohorts.get(cohortDate);
            if (groupKey != null) {
                Set<Long> filtered = new HashSet<>();
                for (Long userId : users) {
                    if (groupKey.equals(classify(factMap.get(userId) == null ? null
                            : factMap.get(userId).get(cohortDate)))) {
                        filtered.add(userId);
                    }
                }
                users = filtered;
            }
            if (users.isEmpty()) {
                continue;
            }
            Map<String, Object> retained = new LinkedHashMap<>();
            Map<String, Object> values = new LinkedHashMap<>();
            for (int day : DEFAULT_DAYS) {
                LocalDate target = cohortDate.plusDays(day);
                if (target.isAfter(today)) {
                    retained.put(String.valueOf(day), null);
                    values.put(String.valueOf(day), null);
                    continue;
                }
                long hit = 0;
                for (Long userId : users) {
                    if (isReturned(retentionType, userId, target, activityDays, playDays)) {
                        hit++;
                    }
                }
                retained.put(String.valueOf(day), hit);
                values.put(String.valueOf(day), rate(hit, users.size()));
                long[] cell = curve.get(day);
                cell[0] += hit;
                cell[1] += users.size();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", cohortDate.toString());
            row.put("cohortSize", users.size());
            row.put("retained", retained);
            row.put("values", values);
            rows.add(row);
        }

        List<Map<String, Object>> curvePoints = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : curve.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", entry.getKey());
            point.put("retained", entry.getValue()[0]);
            point.put("sample", entry.getValue()[1]);
            point.put("rate", entry.getValue()[1] == 0 ? null
                    : rate(entry.getValue()[0], entry.getValue()[1]));
            curvePoints.add(point);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", retentionType);
        result.put("typeLabel", label(retentionType));
        result.put("start", rangeStart.toString());
        result.put("end", rangeEnd.toString());
        result.put("group", groupKey);
        result.put("groupLabel", groupKey == null ? "全部用户" : GROUPS.get(groupKey));
        result.put("rows", rows);
        result.put("curve", curvePoints);
        result.put("groups", groupComparison(retentionType, cohorts, factMap, activityDays, playDays, today));
        result.put("alerts", retentionAlerts(retentionType, cohortDates, cohorts, groupKey, factMap,
                activityDays, playDays, today));
        result.put("scannedRows", facts.size());
        result.put("truncated", truncated);
        result.put("note", "T+1 口径；当日数据请以实时看板为准。回访定义："
                + ("play".equals(retentionType) ? "当日有有效播放" : "当日有任意埋点"));
        return result;
    }

    // ================================================================== cohort

    private Map<LocalDate, Set<Long>> buildCohorts(String type, LocalDate start, LocalDate end,
                                                   List<RetentionFactRow> facts,
                                                   Map<Long, Set<LocalDate>> playDays) {
        Map<LocalDate, Set<Long>> cohorts = new LinkedHashMap<>();
        if ("new".equals(type)) {
            for (RetentionDayRow row : analyticsMapper.registeredUsers(start, end)) {
                if (row.getUserId() != null && row.getDt() != null) {
                    cohorts.computeIfAbsent(row.getDt(), k -> new LinkedHashSet<>()).add(row.getUserId());
                }
            }
            return cohorts;
        }
        if ("play".equals(type)) {
            for (Map.Entry<Long, Set<LocalDate>> entry : playDays.entrySet()) {
                for (LocalDate day : entry.getValue()) {
                    if (!day.isBefore(start) && !day.isAfter(end)) {
                        cohorts.computeIfAbsent(day, k -> new LinkedHashSet<>()).add(entry.getKey());
                    }
                }
            }
            return cohorts;
        }
        for (RetentionFactRow fact : facts) {
            if (fact.getUserId() != null && fact.getDt() != null
                    && !fact.getDt().isBefore(start) && !fact.getDt().isAfter(end)) {
                cohorts.computeIfAbsent(fact.getDt(), k -> new LinkedHashSet<>()).add(fact.getUserId());
            }
        }
        return cohorts;
    }

    private static boolean isReturned(String type, Long userId, LocalDate target,
                                      Map<Long, Set<LocalDate>> activityDays,
                                      Map<Long, Set<LocalDate>> playDays) {
        if ("play".equals(type)) {
            Set<LocalDate> days = playDays.get(userId);
            return days != null && days.contains(target);
        }
        Set<LocalDate> days = activityDays.get(userId);
        return days != null && days.contains(target);
    }

    /** 分群划分按 cohort 当日的首日行为；没有当日行为的用户不进入任何分群。 */
    private static String classify(RetentionFactRow fact) {
        if (fact == null) {
            return null;
        }
        long plays = value(fact.getPlays());
        if (value(fact.getSocial()) > 0) {
            return "social";
        }
        if (value(fact.getRoom()) > 0) {
            return "room";
        }
        if (value(fact.getAi()) > 0) {
            return "ai";
        }
        if (plays >= 10) {
            return "heavy";
        }
        if (plays <= 2) {
            return "light";
        }
        return null;
    }

    // ================================================================== 分群对比

    private List<Map<String, Object>> groupComparison(String type, Map<LocalDate, Set<Long>> cohorts,
                                                      Map<Long, Map<LocalDate, RetentionFactRow>> factMap,
                                                      Map<Long, Set<LocalDate>> activityDays,
                                                      Map<Long, Set<LocalDate>> playDays,
                                                      LocalDate today) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(groupRow("ALL", "全部用户", type, cohorts, factMap, activityDays, playDays, today, true));
        for (Map.Entry<String, String> entry : GROUPS.entrySet()) {
            Map<LocalDate, Set<Long>> subset = new LinkedHashMap<>();
            for (Map.Entry<LocalDate, Set<Long>> cohort : cohorts.entrySet()) {
                Set<Long> users = new LinkedHashSet<>();
                for (Long userId : cohort.getValue()) {
                    Map<LocalDate, RetentionFactRow> byDay = factMap.get(userId);
                    if (entry.getKey().equals(classify(byDay == null ? null : byDay.get(cohort.getKey())))) {
                        users.add(userId);
                    }
                }
                if (!users.isEmpty()) {
                    subset.put(cohort.getKey(), users);
                }
            }
            if (!subset.isEmpty()) {
                result.add(groupRow(entry.getKey(), entry.getValue(), type, subset, factMap,
                        activityDays, playDays, today, false));
            }
        }
        return result;
    }

    private Map<String, Object> groupRow(String key, String label, String type,
                                         Map<LocalDate, Set<Long>> cohorts,
                                         Map<Long, Map<LocalDate, RetentionFactRow>> factMap,
                                         Map<Long, Set<LocalDate>> activityDays,
                                         Map<Long, Set<LocalDate>> playDays,
                                         LocalDate today, boolean overall) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        long cohortUsers = 0;
        for (Set<Long> users : cohorts.values()) {
            cohortUsers += users.size();
        }
        row.put("cohortUsers", cohortUsers);
        for (int day : new int[]{1, 7}) {
            long hit = 0;
            long sample = 0;
            for (Map.Entry<LocalDate, Set<Long>> cohort : cohorts.entrySet()) {
                LocalDate target = cohort.getKey().plusDays(day);
                if (target.isAfter(today)) {
                    continue;
                }
                sample += cohort.getValue().size();
                for (Long userId : cohort.getValue()) {
                    if (isReturned(type, userId, target, activityDays, playDays)) {
                        hit++;
                    }
                }
            }
            row.put("d" + day, sample == 0 ? null : rate(hit, sample));
            row.put("d" + day + "Sample", sample);
            row.put("d" + day + "Ci", wilson(hit, sample));
        }
        if (!overall) {
            Double d1 = (Double) row.get("d1");
            row.put("note", d1 == null ? "样本不足" : (d1 >= 50d ? "留存高于均值" : "留存低于均值"));
        }
        return row;
    }

    // ================================================================== 告警

    /** BR-26-074：最近一个可算 D1 的 cohort 与上一个 cohort 相比，D1 下降 > 10% 告警。 */
    private List<Map<String, Object>> retentionAlerts(String type, List<LocalDate> dates,
                                                      Map<LocalDate, Set<Long>> cohorts, String groupKey,
                                                      Map<Long, Map<LocalDate, RetentionFactRow>> factMap,
                                                      Map<Long, Set<LocalDate>> activityDays,
                                                      Map<Long, Set<LocalDate>> playDays,
                                                      LocalDate today) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date : dates) {
            LocalDate target = date.plusDays(1);
            if (target.isAfter(today)) {
                continue;
            }
            Set<Long> users = cohorts.get(date);
            if (users == null || users.isEmpty()) {
                continue;
            }
            long hit = 0;
            long sample = 0;
            for (Long userId : users) {
                if (groupKey != null) {
                    Map<LocalDate, RetentionFactRow> byDay = factMap.get(userId);
                    if (!groupKey.equals(classify(byDay == null ? null : byDay.get(date)))) {
                        continue;
                    }
                }
                sample++;
                if (isReturned(type, userId, target, activityDays, playDays)) {
                    hit++;
                }
            }
            if (sample >= 30) {
                points.add(point(date, hit, sample));
            }
        }
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (points.size() < 2) {
            return alerts;
        }
        Map<String, Object> current = points.get(points.size() - 1);
        Map<String, Object> previous = points.get(points.size() - 2);
        double currentRate = (Double) current.get("d1");
        double previousRate = (Double) previous.get("d1");
        if (previousRate > 0) {
            double drop = (previousRate - currentRate) / previousRate * 100d;
            if (drop > 10d) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("type", "RETENTION_D1_DROP");
                alert.put("date", current.get("date"));
                alert.put("prevDate", previous.get("date"));
                alert.put("currentD1", currentRate);
                alert.put("prevD1", previousRate);
                alert.put("dropPct", round(drop));
                alert.put("message", "D1 留存环比下降 " + round(drop) + "%，超过 10% 阈值");
                alerts.add(alert);
            }
        }
        return alerts;
    }

    private static Map<String, Object> point(LocalDate date, long hit, long sample) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("date", date.toString());
        point.put("d1", rate(hit, sample));
        point.put("sample", sample);
        return point;
    }

    // ================================================================== 工具

    /** Wilson 95% 置信区间（百分比）。 */
    private static Map<String, Object> wilson(long hit, long sample) {
        Map<String, Object> ci = new LinkedHashMap<>();
        if (sample <= 0) {
            ci.put("low", null);
            ci.put("high", null);
            return ci;
        }
        double p = hit / (double) sample;
        double denominator = 1 + Z95 * Z95 / sample;
        double center = (p + Z95 * Z95 / (2d * sample)) / denominator;
        double margin = Z95 * Math.sqrt(p * (1 - p) / sample + Z95 * Z95 / (4d * sample * sample))
                / denominator;
        ci.put("low", round(Math.max(0d, (center - margin) * 100d)));
        ci.put("high", round(Math.min(1d, (center + margin) * 100d)));
        return ci;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static double rate(long numerator, long denominator) {
        if (denominator == 0) {
            return 0d;
        }
        return round(numerator * 100d / denominator);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String label(String type) {
        if ("new".equals(type)) {
            return "新增留存";
        }
        return "play".equals(type) ? "播放留存" : "活跃留存";
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
    }
}
