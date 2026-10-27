package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.dto.FunnelReq;
import com.example.newmusic.module.dataplatform.entity.EventDef;
import com.example.newmusic.module.dataplatform.mapper.AnalyticsQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.EventDefMapper;
import com.example.newmusic.module.dataplatform.model.FunnelRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 漏斗分析（FR-26-006）。
 *
 * <p>口径与 PRD 保持一致：按人（或设备）归因、步骤必须有序且时间递增（BR-26-059）、
 * 必须落在归因窗口内（BR-26-060）、每步同时给出步间转化与整体转化（BR-26-063）、
 * 流失人数与流失率并高亮最大流失环节（BR-26-064）。
 *
 * <p>本地以 MySQL ODS 明细现算，行数由 {@code funnel-max-rows} 兜住；超过上限会置
 * {@code truncated=true}，前端必须提示「结果已截断」，不能默默给出偏小的转化率。
 */
@Slf4j
@Service
public class FunnelService {

    private static final Set<String> COLUMN_FILTERS =
            new HashSet<>(Arrays.asList("page_id", "platform", "os", "network", "region"));
    private static final Map<String, String> DIMENSION_COLUMNS = new HashMap<>();
    private static final List<Map<String, Object>> PRESETS = new ArrayList<>();

    static {
        DIMENSION_COLUMNS.put("platform", "platform");
        DIMENSION_COLUMNS.put("os", "os");
        DIMENSION_COLUMNS.put("network", "network");
        DIMENSION_COLUMNS.put("region", "region");

        PRESETS.add(preset("activation", "新用户激活漏斗", 168,
                "打开首页 → 播放第一首歌 → 播放 ≥ 3 首（有效） → 7 日内回访。"
                        + "PRD 的「次日回访」按归因窗口 7 天内的再次 page_view 近似，"
                        + "精确的次日口径看留存报表。",
                step("page_view", "打开首页", filter("page_id", "home")),
                step("player_song_play", "播放第一首歌", null),
                stepCount("player_valid_play", "播放 ≥ 3 首（有效）", 3),
                step("page_view", "回访（7 日内）", null)));
        PRESETS.add(preset("register", "注册漏斗", 1,
                "访问注册页 → 完成注册 → 首次播放（同会话 30 分钟近似为 1 小时）。"
                        + "PRD 的「点击注册」「填写信息」没有独立埋点，合并进「访问注册页 → 完成注册」。",
                step("page_view", "访问注册页", filter("page_id", "register")),
                step("user_register_success", "完成注册", null),
                step("player_song_play", "首次播放", null)));
        PRESETS.add(preset("search", "搜索漏斗", 1,
                "提交搜索 → 点击结果 → 播放。PRD 的「点击搜索 / 输入」都发生在提交前，"
                        + "没有独立事件，统一以 search_submit 为第一步。",
                step("search_submit", "提交搜索", null),
                step("search_result_click", "点击结果", null),
                step("player_song_play", "播放", null)));
        PRESETS.add(preset("room", "音乐房漏斗", 168,
                "看到房间入口 → 进入房间 → 停留 > 1 分钟 → 互动（点歌） → 再次进入。",
                step("page_view", "看到房间入口", filter("page_id", "room")),
                step("room_enter_success", "进入房间", null),
                step("room_exit", "停留 > 1 分钟", filter("stay_sec", ">=60")),
                step("room_song_order", "互动（点歌）", null),
                step("room_enter_success", "再次进入", null)));
        PRESETS.add(preset("social", "社交漏斗", 720,
                "浏览动态 → 点赞/评论 → 关注 → 发布动态。",
                step("page_view", "浏览动态", filter("page_id", "feed")),
                step("post_like_click", "点赞/评论", null),
                step("user_follow_click", "关注", null),
                step("post_publish_success", "发布动态", null)));
        PRESETS.add(preset("ai_search", "AI 搜索漏斗", 1,
                "AI 搜索提交 → AI 解析成功 → 点击结果 → 播放。",
                step("search_submit", "AI 搜索提交", filter("scene", "ai_search")),
                step("ai_call", "AI 解析成功", filter("scene", "ai_search")),
                step("search_result_click", "点击结果", null),
                step("player_song_play", "播放", null)));
        PRESETS.add(preset("playlist", "歌单漏斗", 168,
                "浏览歌单 → 播放 → 收藏歌单 → 自己创建歌单。"
                        + "创建歌单没有独立埋点，用「进入歌单编辑页」的 page_view 近似。",
                step("page_view", "浏览歌单", filter("page_id", "playlist")),
                step("player_song_play", "播放", null),
                step("playlist_collect_click", "收藏歌单", null),
                step("page_view", "创建歌单", filter("page_id", "playlist_create"))));
    }

    private final AnalyticsQueryMapper analyticsMapper;
    private final EventDefMapper eventDefMapper;
    private final DataPlatformProperties properties;
    private final ObjectMapper objectMapper;

    public FunnelService(AnalyticsQueryMapper analyticsMapper, EventDefMapper eventDefMapper,
                         DataPlatformProperties properties, ObjectMapper objectMapper) {
        this.analyticsMapper = analyticsMapper;
        this.eventDefMapper = eventDefMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 7 个预置漏斗（AC-26-006-1）。 */
    public List<Map<String, Object>> presets() {
        return PRESETS;
    }

    public Map<String, Object> query(FunnelReq req) {
        if (req == null || req.getSteps() == null || req.getSteps().size() < 2) {
            throw new BizException(ResultCode.PARAM_INVALID, "漏斗至少需要 2 个步骤");
        }
        if (req.getSteps().size() > 10) {
            throw new BizException(ResultCode.PARAM_INVALID, "漏斗最多支持 10 个步骤");
        }
        LocalDate end = parseDate(req.getEnd(), LocalDate.now());
        LocalDate start = parseDate(req.getStart(), end.minusDays(6));
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        if (ChronoUnit.DAYS.between(start, end) > 180) {
            throw new BizException(ResultCode.PARAM_INVALID, "漏斗查询区间不能超过 180 天");
        }

        List<FunnelReq.Step> steps = req.getSteps();
        List<String> eventNames = new ArrayList<>();
        Map<String, EventDef> definitions = new HashMap<>();
        for (EventDef def : eventDefMapper.selectList(Wrappers.<EventDef>lambdaQuery()
                .in(EventDef::getEventName, collectNames(steps))
                .eq(EventDef::getStatus, 1))) {
            definitions.put(def.getEventName(), def);
        }
        for (FunnelReq.Step step : steps) {
            if (step.getEventName() == null || step.getEventName().trim().isEmpty()) {
                throw new BizException(ResultCode.PARAM_INVALID, "步骤事件名不能为空");
            }
            step.setEventName(step.getEventName().trim());
            if (!definitions.containsKey(step.getEventName())) {
                throw new BizException(ResultCode.PARAM_INVALID,
                        "事件 " + step.getEventName() + " 未在埋点定义中登记（BR-26-001）");
            }
            if (!eventNames.contains(step.getEventName())) {
                eventNames.add(step.getEventName());
            }
            if (step.getMinCount() == null || step.getMinCount() < 1) {
                step.setMinCount(1);
            }
        }

        int windowHours = req.getWindowHours() == null || req.getWindowHours() <= 0
                ? 168 : Math.min(req.getWindowHours(), 24 * 90);
        int limit = Math.max(1000, properties.getFunnelMaxRows());
        List<FunnelRow> rows = analyticsMapper.funnelRows(start, end, eventNames,
                dimensionColumn(req.getDimension()), blankToNull(req.getDimensionValue()), limit);

        boolean userSubject = !"device".equalsIgnoreCase(req.getSubject());
        boolean byDimension = req.getDimension() != null && !req.getDimension().trim().isEmpty();
        List<Map<String, Object>> groups = new ArrayList<>();
        if (byDimension) {
            Map<String, List<FunnelRow>> buckets = group(rows, userSubject, req.getDimension());
            for (Map.Entry<String, List<FunnelRow>> entry : buckets.entrySet()) {
                StepResult result = computeBucket(entry.getValue(), steps, windowHours, userSubject);
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("dimensionValue", entry.getKey());
                group.put("steps", result.toSteps(steps));
                group.put("totalUsers", result.firstCount);
                group.put("overallConversion", result.overallRate());
                groups.add(group);
            }
        }

        StepResult total = computeBucket(rows, steps, windowHours, userSubject);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", req.getName() == null ? "自定义漏斗" : req.getName());
        result.put("start", start.toString());
        result.put("end", end.toString());
        result.put("subject", userSubject ? "user" : "device");
        result.put("windowHours", windowHours);
        result.put("dimension", req.getDimension());
        result.put("dimensionValue", req.getDimensionValue());
        result.put("scannedRows", rows.size());
        result.put("truncated", rows.size() >= limit);
        result.put("totalUsers", total.firstCount);
        result.put("overallConversion", total.overallRate());
        result.put("steps", total.toSteps(steps));
        result.put("highlightStep", total.highlightStep);
        result.put("groups", groups);
        result.put("sampleUsers", total.sampleUsers);
        result.put("definitions", definitions.values());
        return result;
    }

    // ================================================================== 计算

    /**
     * 按主体分组，组内按服务端时间升序。
     *
     * <p>SQL 已按 {@code COALESCE(user_id,0), device_id, server_time} 排序；这里只做归桶，
     * 不重新排序，避免在 20 万行上做一次多余的排序。
     */
    private Map<String, List<FunnelRow>> group(List<FunnelRow> rows, boolean userSubject, String dimension) {
        Map<String, List<FunnelRow>> buckets = new LinkedHashMap<>();
        boolean byDimension = dimension != null && !dimension.trim().isEmpty();
        for (FunnelRow row : rows) {
            String subject = userSubject
                    ? (row.getUserId() == null ? null : "u:" + row.getUserId())
                    : (row.getDeviceId() == null ? null : "d:" + row.getDeviceId());
            if (subject == null) {
                continue;
            }
            String bucket = byDimension ? dimensionValue(row, dimension) : "ALL";
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
        }
        return buckets;
    }

    /** 一个桶（全量或某个维度取值）里逐人计算后汇总。 */
    private StepResult computeBucket(List<FunnelRow> rows, List<FunnelReq.Step> steps,
                                     int windowHours, boolean userSubject) {
        StepResult merged = new StepResult(steps.size());
        for (List<FunnelRow> userRows : group(rows, userSubject, null).values()) {
            StepResult one = computeUser(userRows, steps, windowHours, userSubject);
            if (one != null) {
                merge(merged, one);
            }
        }
        return merged;
    }

    private StepResult computeUser(List<FunnelRow> userRows, List<FunnelReq.Step> steps,
                                   int windowHours, boolean userSubject) {
        StepResult result = new StepResult(steps.size());
        if (userRows.isEmpty()) {
            return null;
        }
        int stepIdx = 0;
        int matched = 0;
        boolean first = false;
        long windowEnd = 0;
        String firstUser = null;
        for (FunnelRow row : userRows) {
            LocalDateTime time = row.getServerTime();
            if (time == null) {
                continue;
            }
            long ts = toEpochSecond(time);
            if (first && ts > windowEnd) {
                break;
            }
            FunnelReq.Step step = steps.get(stepIdx);
            if (!matches(row, step)) {
                continue;
            }
            matched++;
            if (matched < step.getMinCount()) {
                continue;
            }
            // 这一步达成：时间取「满足 minCount 的那一次」
            result.counts[stepIdx]++;
            if (stepIdx == 0) {
                first = true;
                windowEnd = ts + windowHours * 3600L;
                firstUser = userSubject ? String.valueOf(row.getUserId()) : row.getDeviceId();
                result.firstCount++;
            }
            if (stepIdx == steps.size() - 1 && result.sampleUsers.size() < 20) {
                String subject = userSubject ? String.valueOf(row.getUserId()) : row.getDeviceId();
                if (subject != null) {
                    result.sampleUsers.add(subject);
                }
            }
            matched = 0;
            stepIdx++;
            if (stepIdx >= steps.size()) {
                break;
            }
        }
        if (!first) {
            return null;
        }
        return result;
    }

    private void merge(StepResult target, StepResult one) {
        target.firstCount += one.firstCount;
        for (int i = 0; i < one.counts.length; i++) {
            target.counts[i] += one.counts[i];
        }
        for (String user : one.sampleUsers) {
            if (target.sampleUsers.size() >= 20) {
                break;
            }
            if (!target.sampleUsers.contains(user)) {
                target.sampleUsers.add(user);
            }
        }
    }

    private boolean matches(FunnelRow row, FunnelReq.Step step) {
        if (!step.getEventName().equals(row.getEventName())) {
            return false;
        }
        if (step.getFilters() == null || step.getFilters().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : step.getFilters().entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            if (key == null || expected == null || expected.isEmpty()) {
                continue;
            }
            String actual;
            if (COLUMN_FILTERS.contains(key)) {
                actual = columnValue(row, key);
            } else {
                actual = propValue(row.getProps(), key);
            }
            if (!compare(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    /** 支持 {@code >=n / <=n / >n / <n / =v / !=v}，其余按忽略大小写全等。 */
    private boolean compare(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        String op = null;
        for (String candidate : new String[]{">=", "<=", "!=", ">", "<", "="}) {
            if (expected.startsWith(candidate)) {
                op = candidate;
                break;
            }
        }
        if (op == null) {
            return actual.equalsIgnoreCase(expected);
        }
        String right = expected.substring(op.length()).trim();
        if (">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op)) {
            Double left = number(actual);
            Double target = number(right);
            if (left == null || target == null) {
                return false;
            }
            int cmp = Double.compare(left, target);
            if (">=".equals(op)) {
                return cmp >= 0;
            }
            if ("<=".equals(op)) {
                return cmp <= 0;
            }
            return ">".equals(op) ? cmp > 0 : cmp < 0;
        }
        boolean equals = actual.equalsIgnoreCase(right);
        return "!=".equals(op) != equals;
    }

    private static Double number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private String propValue(String props, String key) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(props).get(key);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String columnValue(FunnelRow row, String key) {
        switch (key) {
            case "page_id":
                return row.getPageId();
            case "platform":
                return row.getPlatform();
            case "os":
                return row.getOs();
            case "network":
                return row.getNetwork();
            case "region":
                return row.getRegion();
            default:
                return null;
        }
    }

    private static String dimensionValue(FunnelRow row, String dimension) {
        switch (dimension) {
            case "platform":
                return valueOrUnknown(row.getPlatform());
            case "os":
                return valueOrUnknown(row.getOs());
            case "network":
                return valueOrUnknown(row.getNetwork());
            case "region":
                return valueOrUnknown(row.getRegion());
            default:
                return "ALL";
        }
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isEmpty() ? "未知" : value;
    }

    private static String dimensionColumn(String dimension) {
        if (dimension == null || dimension.trim().isEmpty()) {
            return null;
        }
        String column = DIMENSION_COLUMNS.get(dimension.trim());
        if (column == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "漏斗分维度只支持 platform/os/network/region");
        }
        return column;
    }

    private static List<String> collectNames(List<FunnelReq.Step> steps) {
        List<String> names = new ArrayList<>();
        for (FunnelReq.Step step : steps) {
            if (step.getEventName() != null && !step.getEventName().trim().isEmpty()) {
                names.add(step.getEventName().trim());
            }
        }
        return names.isEmpty() ? Collections.singletonList("__none__") : names;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static long toEpochSecond(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
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

    // ================================================================== 结果结构

    private static class StepResult {
        final long[] counts;
        long firstCount;
        final List<String> sampleUsers = new ArrayList<>();
        String highlightStep;

        StepResult(int size) {
            this.counts = new long[size];
        }

        double overallRate() {
            return firstCount == 0 ? 0d : rate(counts[counts.length - 1], firstCount);
        }

        List<Map<String, Object>> toSteps(List<FunnelReq.Step> steps) {
            List<Map<String, Object>> list = new ArrayList<>(steps.size());
            long maxDrop = -1;
            int maxDropIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                FunnelReq.Step step = steps.get(i);
                long prev = i == 0 ? firstCount : counts[i - 1];
                long current = counts[i];
                long drop = Math.max(0, prev - current);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", i + 1);
                item.put("eventName", step.getEventName());
                item.put("label", step.getLabel() == null ? step.getEventName() : step.getLabel());
                item.put("minCount", step.getMinCount());
                item.put("users", current);
                item.put("stepRate", prev == 0 ? 0d : rate(current, prev));
                item.put("overallRate", firstCount == 0 ? 0d : rate(current, firstCount));
                item.put("dropOffUsers", drop);
                item.put("dropOffRate", prev == 0 ? 0d : rate(drop, prev));
                if (i > 0 && drop > maxDrop) {
                    maxDrop = drop;
                    maxDropIndex = i;
                }
                list.add(item);
            }
            if (maxDropIndex >= 0) {
                highlightStep = String.valueOf(maxDropIndex + 1);
                list.get(maxDropIndex).put("highlight", true);
            }
            return list;
        }

        private static double rate(long numerator, long denominator) {
            if (denominator == 0) {
                return 0d;
            }
            return BigDecimal.valueOf(numerator * 100d / denominator)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
    }

    // ================================================================== 预置构造

    private static Map<String, Object> preset(String key, String name, int windowHours,
                                              String description, Map<String, Object>... steps) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("name", name);
        item.put("windowHours", windowHours);
        item.put("description", description);
        item.put("steps", Arrays.asList(steps));
        return item;
    }

    private static Map<String, Object> step(String eventName, String label, Map<String, String> filters) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventName", eventName);
        item.put("label", label);
        item.put("filters", filters);
        item.put("minCount", 1);
        return item;
    }

    private static Map<String, Object> stepCount(String eventName, String label, int minCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventName", eventName);
        item.put("label", label);
        item.put("filters", null);
        item.put("minCount", minCount);
        return item;
    }

    private static Map<String, String> filter(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
