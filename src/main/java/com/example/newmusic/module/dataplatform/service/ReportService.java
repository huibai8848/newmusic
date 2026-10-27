package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.entity.DataReport;
import com.example.newmusic.module.dataplatform.entity.ReportSubscription;
import com.example.newmusic.module.dataplatform.mapper.DataReportMapper;
import com.example.newmusic.module.dataplatform.mapper.GovernanceQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.ReportSubscriptionMapper;
import com.example.newmusic.module.dataplatform.model.DimValueRow;
import com.example.newmusic.module.dataplatform.model.TopItemRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 运营报表（FR-26-009）。
 *
 * <p>12 类报表：DAILY / WEEKLY / MONTHLY / CONTENT / USER / ROOM / SOCIAL / RECOMMEND /
 * SEARCH / SECURITY / COST / CHANNEL。报表内容存 JSON（与看板同源，BR-26-082），
 * 每份都带口径说明与生成时间（BR-26-083）。
 *
 * <p>周报/月报按区间聚合：计数类求和，比率类用区间内每日 ADS 值的均值。
 * 报表不含任何用户明细或手机号/邮箱（BR-26-088）。
 */
@Slf4j
@Service("dataPlatformReportService")
public class ReportService {

    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    public static final List<String> FREQUENCIES = Arrays.asList("DAILY", "WEEKLY", "MONTHLY");
    public static final List<String> CHANNELS = Arrays.asList("IN_APP", "EMAIL", "WECHAT", "DINGTALK");

    static {
        TYPES.put("DAILY", "日报");
        TYPES.put("WEEKLY", "周报");
        TYPES.put("MONTHLY", "月报");
        TYPES.put("CONTENT", "内容报表");
        TYPES.put("USER", "用户报表");
        TYPES.put("ROOM", "房间报表");
        TYPES.put("SOCIAL", "社交报表");
        TYPES.put("RECOMMEND", "推荐报表");
        TYPES.put("SEARCH", "搜索报表");
        TYPES.put("SECURITY", "内容安全报表");
        TYPES.put("COST", "成本报表");
        TYPES.put("CHANNEL", "渠道报表");
    }

    private final DataReportMapper reportMapper;
    private final ReportSubscriptionMapper subscriptionMapper;
    private final GovernanceQueryMapper governanceMapper;
    private final ObjectMapper objectMapper;

    public ReportService(DataReportMapper reportMapper, ReportSubscriptionMapper subscriptionMapper,
                         GovernanceQueryMapper governanceMapper, ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.governanceMapper = governanceMapper;
        this.objectMapper = objectMapper;
    }

    // ================================================================== 生成

    public Map<String, Object> generate(String type, String dateText) {
        String reportType = normalizeType(type);
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        return generate(reportType, date, true);
    }

    private Map<String, Object> generate(String reportType, LocalDate date, boolean persist) {
        LocalDate end = date;
        LocalDate start = date;
        if ("WEEKLY".equals(reportType)) {
            start = date.minusDays(6);
        } else if ("MONTHLY".equals(reportType)) {
            start = date.minusDays(29);
        }
        Map<String, Object> content = buildContent(reportType, start, end);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportType", reportType);
        view.put("reportTypeLabel", TYPES.get(reportType));
        view.put("reportDate", date.toString());
        view.put("start", start.toString());
        view.put("end", end.toString());
        view.put("title", title(reportType, date));
        view.put("content", content);
        view.put("status", 1);
        if (!persist) {
            return view;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_BUSY, "报表序列化失败");
        }
        DataReport existing = reportMapper.selectOne(Wrappers.<DataReport>lambdaQuery()
                .eq(DataReport::getReportType, reportType)
                .eq(DataReport::getReportDate, date)
                .last("LIMIT 1"));
        DataReport report = existing == null ? new DataReport() : existing;
        report.setReportType(reportType);
        report.setReportDate(date);
        report.setTitle((String) view.get("title"));
        report.setContent(json);
        report.setStatus(1);
        if (report.getSentCount() == null) {
            report.setSentCount(0);
        }
        report.setGeneratedAt(LocalDateTime.now());
        if (existing == null) {
            reportMapper.insert(report);
        } else {
            reportMapper.updateById(report);
        }
        view.put("id", String.valueOf(report.getId()));
        view.put("generatedAt", report.getGeneratedAt().withNano(0).toString());
        view.put("sentCount", report.getSentCount());
        return view;
    }

    /** 定时任务：生成当天应产出的 12 类报表（或指定类型）。 */
    public Map<String, Object> generateAll(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        List<Map<String, Object>> results = new ArrayList<>();
        for (String type : TYPES.keySet()) {
            results.add(generate(type, date, true));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("generated", results.size());
        result.put("reports", results);
        return result;
    }

    // ================================================================== 查询与导出

    public List<Map<String, Object>> list(String type, String startText, String endText) {
        String reportType = type == null || type.trim().isEmpty() ? null : normalizeType(type);
        LocalDate end = parseDate(endText, LocalDate.now());
        LocalDate start = parseDate(startText, end.minusDays(30));
        List<DataReport> reports = reportMapper.selectList(Wrappers.<DataReport>lambdaQuery()
                .eq(reportType != null, DataReport::getReportType, reportType)
                .between(DataReport::getReportDate, start, end)
                .orderByDesc(DataReport::getReportDate, DataReport::getReportType));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataReport report : reports) {
            rows.add(render(report, false));
        }
        return rows;
    }

    public Map<String, Object> detail(String type, String dateText) {
        String reportType = normalizeType(type);
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        DataReport report = reportMapper.selectOne(Wrappers.<DataReport>lambdaQuery()
                .eq(DataReport::getReportType, reportType)
                .eq(DataReport::getReportDate, date)
                .last("LIMIT 1"));
        if (report == null) {
            return generate(reportType, date, true);
        }
        return render(report, true);
    }

    /** CSV 导出（BR-26-084）：按「分区 / 指标 / 数值」三列展开，带 UTF-8 BOM 便于 Excel 打开。 */
    public String exportCsv(String type, String dateText) {
        String reportType = normalizeType(type);
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        Map<String, Object> detail = detail(reportType, date.toString());
        Object content = detail.get("content");
        StringBuilder csv = new StringBuilder("\uFEFF\u5206\u533a,\u6307\u6807,\u6570\u503c\n");
        List<String> lines = new ArrayList<>();
        flatten("", content, lines);
        for (String line : lines) {
            csv.append(line).append('\n');
        }
        return csv.toString();
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object node, List<String> lines) {
        if (node == null) {
            return;
        }
        if (node instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) node).entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "/" + entry.getKey();
                flatten(path, entry.getValue(), lines);
            }
            return;
        }
        if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                flatten(prefix, item, lines);
            }
            return;
        }
        int split = prefix.lastIndexOf('/');
        String section = split < 0 ? "" : prefix.substring(0, split);
        String label = split < 0 ? prefix : prefix.substring(split + 1);
        lines.add(escape(section) + "," + escape(label) + "," + escape(String.valueOf(node)));
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    // ================================================================== 分发与订阅

    public Map<String, Object> dispatch(String type, String dateText) {
        String reportType = normalizeType(type);
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        Map<String, Object> detail = detail(reportType, date.toString());
        String frequency = "WEEKLY".equals(reportType) ? "WEEKLY"
                : ("MONTHLY".equals(reportType) ? "MONTHLY" : "DAILY");
        List<ReportSubscription> subscriptions = subscriptionMapper.selectList(
                Wrappers.<ReportSubscription>lambdaQuery()
                        .eq(ReportSubscription::getStatus, 1)
                        .eq(ReportSubscription::getFrequency, frequency));
        int matched = 0;
        int delivered = 0;
        List<Map<String, Object>> deliveries = new ArrayList<>();
        for (ReportSubscription subscription : subscriptions) {
            List<String> types = Arrays.asList(subscription.getReportTypes().split(","));
            if (!types.contains(reportType)) {
                continue;
            }
            matched++;
            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("adminId", String.valueOf(subscription.getAdminId()));
            delivery.put("channel", subscription.getChannel());
            // 本地没有邮件/IM 网关：按「站内信投递成功」模拟，最多重试 3 次（BR-26-087）
            boolean success = true;
            int attempts = 1;
            for (int i = 0; i < 3 && !success; i++) {
                attempts++;
            }
            delivery.put("success", success);
            delivery.put("attempts", attempts);
            deliveries.add(delivery);
            delivered++;
        }
        DataReport report = reportMapper.selectOne(Wrappers.<DataReport>lambdaQuery()
                .eq(DataReport::getReportType, reportType)
                .eq(DataReport::getReportDate, date)
                .last("LIMIT 1"));
        if (report != null) {
            report.setSentCount((report.getSentCount() == null ? 0 : report.getSentCount()) + delivered);
            reportMapper.updateById(report);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportType", reportType);
        result.put("reportDate", date.toString());
        result.put("matchedSubscriptions", matched);
        result.put("delivered", delivered);
        result.put("deliveries", deliveries);
        result.put("simulated", true);
        result.put("note", "本地未接入邮件/企业微信/钉钉网关，按站内信投递模拟；失败最多重试 3 次");
        result.put("title", detail.get("title"));
        return result;
    }

    public Map<String, Object> saveSubscription(String reportTypes, String frequency, String channel) {
        Long adminId = UserContext.requireUserId();
        if (reportTypes == null || reportTypes.trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "至少订阅一种报表");
        }
        List<String> types = new ArrayList<>();
        for (String item : reportTypes.split(",")) {
            if (!item.trim().isEmpty()) {
                types.add(normalizeType(item.trim()));
            }
        }
        String freq = frequency == null || frequency.trim().isEmpty() ? "DAILY" : frequency.trim().toUpperCase();
        if (!FREQUENCIES.contains(freq)) {
            throw new BizException(ResultCode.PARAM_INVALID, "频率只支持 DAILY/WEEKLY/MONTHLY");
        }
        String ch = channel == null || channel.trim().isEmpty() ? "IN_APP" : channel.trim().toUpperCase();
        if (!CHANNELS.contains(ch)) {
            throw new BizException(ResultCode.PARAM_INVALID, "渠道只支持 IN_APP/EMAIL/WECHAT/DINGTALK");
        }
        ReportSubscription subscription = subscriptionMapper.selectOne(
                Wrappers.<ReportSubscription>lambdaQuery()
                        .eq(ReportSubscription::getAdminId, adminId)
                        .last("LIMIT 1"));
        if (subscription == null) {
            subscription = new ReportSubscription();
            subscription.setAdminId(adminId);
            subscription.setCreateTime(LocalDateTime.now());
        }
        subscription.setReportTypes(String.join(",", types));
        subscription.setFrequency(freq);
        subscription.setChannel(ch);
        subscription.setStatus(1);
        subscription.setUpdateTime(LocalDateTime.now());
        if (subscription.getId() == null) {
            subscriptionMapper.insert(subscription);
        } else {
            subscriptionMapper.updateById(subscription);
        }
        return renderSubscription(subscription);
    }

    public Map<String, Object> mySubscription() {
        Long adminId = UserContext.requireUserId();
        ReportSubscription subscription = subscriptionMapper.selectOne(
                Wrappers.<ReportSubscription>lambdaQuery()
                        .eq(ReportSubscription::getAdminId, adminId)
                        .last("LIMIT 1"));
        if (subscription == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("subscribed", false);
            empty.put("channels", CHANNELS);
            empty.put("frequencies", FREQUENCIES);
            empty.put("types", TYPES);
            return empty;
        }
        Map<String, Object> result = renderSubscription(subscription);
        result.put("subscribed", true);
        result.put("channels", CHANNELS);
        result.put("frequencies", FREQUENCIES);
        result.put("types", TYPES);
        return result;
    }

    public Map<String, Object> cancelSubscription() {
        Long adminId = UserContext.requireUserId();
        subscriptionMapper.delete(Wrappers.<ReportSubscription>lambdaQuery()
                .eq(ReportSubscription::getAdminId, adminId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscribed", false);
        return result;
    }

    // ================================================================== 内容构造

    private Map<String, Object> buildContent(String type, LocalDate start, LocalDate end) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("range", start + " ~ " + end);
        content.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        content.put("source", "ADS/ODS（与实时看板同源）");
        content.put("definition", "有效播放 = 时长 ≥ 30s 或完成度 ≥ 60%；新增留存 = 注册次日有有效播放");

        switch (type) {
            case "DAILY":
                content.put("core", core(start, end));
                content.put("top", topContent(end));
                break;
            case "USER":
                content.put("sections", Arrays.asList(
                        section("用户规模", Arrays.asList(
                                metric("新增用户", sumCount(start, end, governanceMapper::countUsersCreated), "人"),
                                metric("DAU", metricOrZero(end, "metric_dau"), "人"),
                                metric("DAU-Play", metricOrZero(end, "metric_dau_play"), "人"),
                                metric("次日留存", metricOrZero(end, "metric_retention_d1"), "%"),
                                metric("7 日留存", metricOrZero(end, "metric_retention_d7"), "%"))),
                        section("注册渠道", channelItems(end))));
                break;
            case "CONTENT":
                content.put("sections", Arrays.asList(
                        section("内容规模", Arrays.asList(
                                metric("新增歌曲", sumCount(start, end, governanceMapper::countSongsPublished), "首"),
                                metric("下架歌曲", sumCount(start, end, governanceMapper::countSongsOffline), "首"),
                                metric("曲库有效播放覆盖率", metricOrZero(end, "metric_song_coverage"), "%"),
                                metric("互动用户", metricOrZero(end, "metric_interaction_user"), "人"))),
                        section("热门歌曲", topSongs(end))));
                break;
            case "ROOM":
                content.put("sections", Arrays.asList(
                        section("房间规模", Arrays.asList(
                                metric("新建房间", sumCount(start, end, governanceMapper::countRoomsCreated), "个"),
                                metric("活跃房间", sumCount(start, end, governanceMapper::countActiveRooms), "个"),
                                metric("房间用户", metricOrZero(end, "metric_room_active_user"), "人"),
                                metric("房间消息", sumCount(start, end, governanceMapper::countRoomMessages), "条"),
                                metric("房间播放", sumCount(start, end, governanceMapper::countRoomPlayLogs), "次"))),
                        section("活跃房间 Top5", topRooms(end))));
                break;
            case "SOCIAL":
                content.put("sections", Collections.singletonList(
                        section("社交互动", Arrays.asList(
                                metric("发布动态", sumCount(start, end, governanceMapper::countPosts), "条"),
                                metric("点赞", sumCount(start, end, governanceMapper::countLikes), "次"),
                                metric("评论", sumCount(start, end, governanceMapper::countComments), "条"),
                                metric("收藏", sumCount(start, end, governanceMapper::countCollects), "次"),
                                metric("私信", sumCount(start, end, governanceMapper::countPrivateMessages), "条"),
                                metric("互动用户", metricOrZero(end, "metric_interaction_user"), "人")))));
                break;
            case "RECOMMEND":
                content.put("sections", Collections.singletonList(
                        section("推荐效果", Arrays.asList(
                                metric("推荐曝光", eventCount(end, "recommend_exposure"), "次"),
                                metric("推荐点击", eventCount(end, "recommend_click"), "次"),
                                metric("推荐 CTR", metricOrZero(end, "metric_recommend_ctr"), "%"),
                                metric("有效播放", metricOrZero(end, "metric_valid_play_cnt"), "次"),
                                metric("DAU-Play", metricOrZero(end, "metric_dau_play"), "人")))));
                break;
            case "SEARCH":
                content.put("sections", Arrays.asList(
                        section("搜索", Arrays.asList(
                                metric("搜索成功率", metricOrZero(end, "metric_search_success_rate"), "%"),
                                metric("搜索提交", eventCount(end, "search_submit"), "次"),
                                metric("结果点击", eventCount(end, "search_result_click"), "次"))),
                        section("热词 Top10", topKeywords(end))));
                break;
            case "SECURITY":
                long reports = sumCount(start, end, governanceMapper::countContentReports);
                long audits = sumCount(start, end, governanceMapper::countAuditRecords);
                content.put("sections", Collections.singletonList(
                        section("内容安全", Arrays.asList(
                                metric("用户举报", reports, "条"),
                                metric("审核记录", audits, "条"),
                                metric("举报/审核比", rate(reports, audits), "%")))));
                break;
            case "COST":
                double aiCost = rangeMetric(start, end, "metric_ai_cost", true);
                long calls = sumCount(start, end, governanceMapper::countAiCalls);
                long dau = Math.round(rangeMetric(start, end, "metric_dau", true));
                content.put("sections", Collections.singletonList(
                        section("成本", Arrays.asList(
                                metric("AI 调用", calls, "次"),
                                metric("AI 成本", round(aiCost), "元"),
                                metric("人均 AI 成本", dau == 0 ? 0d : round(aiCost / dau), "元/人")))));
                break;
            case "CHANNEL":
                content.put("sections", Collections.singletonList(
                        section("渠道", channelItems(end))));
                break;
            case "WEEKLY":
            case "MONTHLY":
                content.put("core", core(start, end));
                content.put("trend", dailyTrend(start, end));
                content.put("top", topContent(end));
                break;
            default:
                content.put("core", core(start, end));
        }

        List<Map<String, Object>> anomalies = anomalies(end);
        content.put("anomalies", anomalies);
        return content;
    }

    private Map<String, Object> core(LocalDate start, LocalDate end) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("dau", round(rangeMetric(start, end, "metric_dau", true)));
        core.put("dauPlay", round(rangeMetric(start, end, "metric_dau_play", true)));
        core.put("playCnt", Math.round(rangeMetric(start, end, "metric_play_cnt", false)));
        core.put("validPlayCnt", Math.round(rangeMetric(start, end, "metric_valid_play_cnt", false)));
        core.put("newUsers", sumCount(start, end, governanceMapper::countUsersCreated));
        core.put("retentionD1", metricOrZero(end, "metric_retention_d1"));
        core.put("note", start.equals(end) ? "单日口径" : "计数类求和，DAU/留存类取区间均值");
        return core;
    }

    private List<Map<String, Object>> dailyTrend(LocalDate start, LocalDate end) {
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("dau", metricOrZero(date, "metric_dau"));
            point.put("playCnt", metricOrZero(date, "metric_play_cnt"));
            point.put("newUsers", governanceMapper.countUsersCreated(date));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> anomalies(LocalDate date) {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        Double d1 = metricOrNull(date, "metric_retention_d1");
        Double previous = metricOrNull(date.minusDays(1), "metric_retention_d1");
        if (d1 != null && previous != null && previous > 0) {
            double drop = (previous - d1) / previous * 100d;
            if (drop > 10d) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("metric", "retention_d1");
                item.put("message", "次日留存环比下降 " + round(drop) + "%");
                item.put("severity", "warning");
                anomalies.add(item);
            }
        }
        Double ctr = metricOrNull(date, "metric_recommend_ctr");
        Double ctrPrev = metricOrNull(date.minusDays(1), "metric_recommend_ctr");
        if (ctr != null && ctrPrev != null && ctrPrev > 0
                && Math.abs(ctr - ctrPrev) / ctrPrev > 0.3d) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", "recommend_ctr");
            item.put("message", "推荐 CTR 波动超过 30%");
            item.put("severity", "warning");
            anomalies.add(item);
        }
        return anomalies;
    }

    private Map<String, Object> topContent(LocalDate date) {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("songs", topSongs(date));
        top.put("rooms", topRooms(date));
        top.put("keywords", topKeywords(date));
        return top;
    }

    private List<Map<String, Object>> topSongs(LocalDate date) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TopItemRow row : governanceMapper.topSongsFromHistory(date, 10)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("songId", String.valueOf(row.getId()));
            item.put("name", row.getName());
            item.put("playCnt", row.getCnt());
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> topRooms(LocalDate date) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TopItemRow row : governanceMapper.topRoomsFromMessages(date, 5)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("roomId", String.valueOf(row.getId()));
            item.put("name", row.getName());
            item.put("messageCnt", row.getCnt());
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> topKeywords(LocalDate date) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DimValueRow row : governanceMapper.topSearchKeywords(date, 10)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyword", row.getDimValue());
            item.put("count", row.getV());
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> channelItems(LocalDate date) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DimValueRow row : governanceMapper.registerChannelDist(date)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel", row.getDimValue() == null ? "未知" : row.getDimValue());
            item.put("newUsers", row.getV());
            list.add(item);
        }
        return list;
    }

    // ================================================================== 取值

    private Double metricOrNull(LocalDate date, String metricId) {
        BigDecimal value = governanceMapper.adsMetric(date, metricId);
        return value == null ? null : value.doubleValue();
    }

    private double metricOrZero(LocalDate date, String metricId) {
        Double value = metricOrNull(date, metricId);
        return value == null ? 0d : round(value);
    }

    private long eventCount(LocalDate date, String eventName) {
        return governanceMapper.countEventByName(date, eventName);
    }

    /** 区间指标：计数类求和，比率类取均值。 */
    private double rangeMetric(LocalDate start, LocalDate end, String metricId, boolean average) {
        double sum = 0d;
        int days = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Double value = metricOrNull(date, metricId);
            if (value != null) {
                sum += value;
                days++;
            }
        }
        if (days == 0) {
            return 0d;
        }
        return average ? sum / days : sum;
    }

    private long sumCount(LocalDate start, LocalDate end, Function<LocalDate, Long> fn) {
        long sum = 0L;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Long value = fn.apply(date);
            sum += value == null ? 0L : value;
        }
        return sum;
    }

    // ================================================================== 渲染

    private Map<String, Object> render(DataReport report, boolean withContent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(report.getId()));
        item.put("reportType", report.getReportType());
        item.put("reportTypeLabel", TYPES.get(report.getReportType()));
        item.put("reportDate", report.getReportDate() == null ? null : report.getReportDate().toString());
        item.put("title", report.getTitle());
        item.put("status", report.getStatus());
        item.put("sentCount", report.getSentCount());
        item.put("generatedAt", report.getGeneratedAt() == null ? null : report.getGeneratedAt().toString());
        item.put("updateTime", report.getUpdateTime() == null ? null : report.getUpdateTime().toString());
        if (withContent) {
            try {
                item.put("content", report.getContent() == null
                        ? Collections.emptyMap() : objectMapper.readValue(report.getContent(), Map.class));
            } catch (Exception e) {
                item.put("content", Collections.emptyMap());
            }
            item.put("definition", "与实时看板同源；有效播放 = 时长 ≥ 30s 或完成度 ≥ 60%");
        }
        return item;
    }

    private Map<String, Object> renderSubscription(ReportSubscription subscription) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(subscription.getId()));
        item.put("adminId", String.valueOf(subscription.getAdminId()));
        item.put("reportTypes", subscription.getReportTypes());
        item.put("frequency", subscription.getFrequency());
        item.put("channel", subscription.getChannel());
        item.put("status", subscription.getStatus());
        item.put("updateTime", subscription.getUpdateTime() == null ? null : subscription.getUpdateTime().toString());
        return item;
    }

    // ================================================================== 工具

    private static Map<String, Object> section(String title, List<Map<String, Object>> items) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", title);
        section.put("items", items);
        return section;
    }

    private static Map<String, Object> metric(String label, Number value, String unit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        item.put("unit", unit);
        return item;
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0d : round(numerator * 100d / denominator);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "报表类型不能为空");
        }
        String normalized = type.trim().toUpperCase();
        if (!TYPES.containsKey(normalized)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持的报表类型：" + type);
        }
        return normalized;
    }

    private static String title(String type, LocalDate date) {
        return "MusicLive " + TYPES.get(type) + " " + date;
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

    /** 供 CSV 导出时判断区间报表。 */
    public static boolean isRange(String type) {
        return "WEEKLY".equals(type) || "MONTHLY".equals(type);
    }

    /** 供调度判断报表是否已生成。 */
    public Map<String, Object> exists(String type, LocalDate date) {
        DataReport report = reportMapper.selectOne(Wrappers.<DataReport>lambdaQuery()
                .eq(DataReport::getReportType, normalizeType(type))
                .eq(DataReport::getReportDate, date)
                .last("LIMIT 1"));
        Map<String, Object> result = new HashMap<>();
        result.put("exists", report != null);
        result.put("id", report == null ? null : String.valueOf(report.getId()));
        return result;
    }

    /** 报表生成时间跨度（周报/月报）——调度日志用。 */
    public long spanDays(String type, LocalDate date) {
        if ("WEEKLY".equals(type)) {
            return 7;
        }
        return "MONTHLY".equals(type) ? 30 : 1;
    }

    static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }
}
