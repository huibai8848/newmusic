package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.dto.FunnelReq;
import com.example.newmusic.module.dataplatform.mapper.AnalyticsQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.GovernanceQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.WarehouseMapper;
import com.example.newmusic.module.dataplatform.model.DimValueRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数仓分层加工 ODS → DWD → DWS → ADS（FR-26-010）。
 *
 * <p>幂等（BR-26-093）：DWD 用唯一键 {@code INSERT IGNORE}；DWS/ADS 先删当日分区再整段重算，
 * 重跑结果与首次一致，晚到数据会被修正。每次运行返回各层行数，便于对账。
 *
 * <p>本机没有 ClickHouse/Flink，分层表都落在 MySQL；口径与 PRD 一致，只是物化介质不同。
 */
@Slf4j
@Service
public class WarehouseEtlService {

    private static final Map<String, String> METRIC_NAMES = new LinkedHashMap<>();
    private static final List<String> DIMENSIONS = Arrays.asList("platform", "os", "network", "region");

    static {
        METRIC_NAMES.put("metric_dau", "日活跃用户数");
        METRIC_NAMES.put("metric_dau_play", "DAU-Play（北极星）");
        METRIC_NAMES.put("metric_play_cnt", "播放次数");
        METRIC_NAMES.put("metric_valid_play_cnt", "有效播放次数");
        METRIC_NAMES.put("metric_play_duration", "播放时长");
        METRIC_NAMES.put("metric_avg_play_per_user", "人均播放次数");
        METRIC_NAMES.put("metric_new_user_cnt", "新增用户数");
        METRIC_NAMES.put("metric_retention_d1", "次日留存率");
        METRIC_NAMES.put("metric_retention_d7", "7 日留存率");
        METRIC_NAMES.put("metric_completion_rate", "播放完成率");
        METRIC_NAMES.put("metric_song_coverage", "曲库有效播放覆盖率");
        METRIC_NAMES.put("metric_recommend_ctr", "推荐点击率");
        METRIC_NAMES.put("metric_search_success_rate", "搜索成功率");
        METRIC_NAMES.put("metric_room_active_user", "房间活跃用户数");
        METRIC_NAMES.put("metric_interaction_user", "互动用户数");
        METRIC_NAMES.put("metric_content_produce", "内容生产量");
        METRIC_NAMES.put("metric_ai_cost", "AI 调用成本（元）");
        METRIC_NAMES.put("metric_online_user", "当前在线用户数");
        METRIC_NAMES.put("metric_track_success_rate", "埋点上报成功率");
        METRIC_NAMES.put("metric_funnel_activation", "新用户激活漏斗转化率");
        METRIC_NAMES.put("metric_content_report_cnt", "内容报表产出数");
        METRIC_NAMES.put("metric_data_quality_score", "数据质量得分");
    }

    private final WarehouseMapper warehouseMapper;
    private final GovernanceQueryMapper governanceMapper;
    private final AnalyticsQueryMapper analyticsMapper;
    private final FunnelService funnelService;
    private final DataPlatformProperties properties;

    public WarehouseEtlService(WarehouseMapper warehouseMapper, GovernanceQueryMapper governanceMapper,
                               AnalyticsQueryMapper analyticsMapper, FunnelService funnelService,
                               DataPlatformProperties properties) {
        this.warehouseMapper = warehouseMapper;
        this.governanceMapper = governanceMapper;
        this.analyticsMapper = analyticsMapper;
        this.funnelService = funnelService;
        this.properties = properties;
    }

    // ================================================================== 入口

    public Map<String, Object> run(String dateText) {
        return run(parseDate(dateText, LocalDate.now().minusDays(1)));
    }

    public Map<String, Object> run(LocalDate date) {
        if (date == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "日期不能为空");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能加工未来日期的数据");
        }
        long begin = System.currentTimeMillis();
        int dwd = warehouseMapper.insertDwdFromOds(date, properties.getValidPlaySeconds(),
                BigDecimal.valueOf(properties.getValidPlayRatio()));
        int dwsUserDeleted = warehouseMapper.deleteDwsUser(date);
        int dwsUsers = warehouseMapper.insertDwsUser(date);
        int dwsSongDeleted = warehouseMapper.deleteDwsSong(date);
        int dwsSongs = warehouseMapper.insertDwsSong(date);
        int adsDeleted = warehouseMapper.deleteAds(date);

        List<Map<String, Object>> metrics = computeMetrics(date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("dwdInserted", dwd);
        result.put("dwsUsers", dwsUsers);
        result.put("dwsSongs", dwsSongs);
        result.put("adsRows", metrics.size());
        result.put("adsDeleted", adsDeleted);
        result.put("dwsUserDeleted", dwsUserDeleted);
        result.put("dwsSongDeleted", dwsSongDeleted);
        result.put("metrics", metrics);
        result.put("costMs", System.currentTimeMillis() - begin);
        result.put("idempotent", true);
        log.info("data-platform etl done date={} dwd={} dwsUser={} dwsSong={} ads={} cost={}ms",
                date, dwd, dwsUsers, dwsSongs, metrics.size(), System.currentTimeMillis() - begin);
        return result;
    }

    /** 回补区间（按天串行，幂等，可重复执行）。 */
    public Map<String, Object> runRange(String startText, String endText) {
        LocalDate end = parseDate(endText, LocalDate.now().minusDays(1));
        LocalDate start = parseDate(startText, end.minusDays(6));
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        if (ChronoUnit.DAYS.between(start, end) > 90) {
            throw new BizException(ResultCode.PARAM_INVALID, "单次回补不能超过 90 天");
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            days.add(run(date));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", start.toString());
        result.put("end", end.toString());
        result.put("days", days.size());
        result.put("results", days);
        return result;
    }

    // ================================================================== 指标计算

    private List<Map<String, Object>> computeMetrics(LocalDate date) {
        List<Map<String, Object>> rows = new ArrayList<>();

        long dau = governanceMapper.countTrackUsers(date);
        long playCnt = governanceMapper.sumDwsUserField("play_cnt", date);
        long validPlayCnt = governanceMapper.sumDwsUserField("valid_play_cnt", date);
        long playDuration = governanceMapper.sumDwsUserField("play_duration", date);
        long dwdCnt = governanceMapper.countDwd(date);
        long complete = governanceMapper.countComplete(date, new BigDecimal("0.9"));
        long newUsers = governanceMapper.countUsersCreated(date);
        long songsTotal = governanceMapper.countSongsTotal();
        long playedSongs = governanceMapper.countPlayedSongs(date);

        addMetric(rows, date, "metric_dau", dau);
        addMetric(rows, date, "metric_dau_play",
                governanceMapper.countDauPlay(date, properties.getDauPlayThreshold()));
        addMetric(rows, date, "metric_play_cnt", playCnt);
        addMetric(rows, date, "metric_valid_play_cnt", validPlayCnt);
        addMetric(rows, date, "metric_play_duration", playDuration);
        addMetric(rows, date, "metric_avg_play_per_user", dau == 0 ? 0d : playCnt / (double) dau);
        addMetric(rows, date, "metric_new_user_cnt", newUsers);
        addMetric(rows, date, "metric_completion_rate", percent(complete, dwdCnt));
        addMetric(rows, date, "metric_song_coverage", percent(playedSongs, songsTotal));
        addMetric(rows, date, "metric_recommend_ctr", percent(
                governanceMapper.countEventByName(date, "recommend_click"),
                governanceMapper.countEventByName(date, "recommend_exposure")));
        addMetric(rows, date, "metric_search_success_rate", percent(
                governanceMapper.countEventByName(date, "search_result_click"),
                governanceMapper.countEventByName(date, "search_submit")));
        addMetric(rows, date, "metric_room_active_user", governanceMapper.countRoomUsers(date));
        addMetric(rows, date, "metric_interaction_user", governanceMapper.countInteractionUsers(date));
        addMetric(rows, date, "metric_content_produce", governanceMapper.countPosts(date)
                + governanceMapper.countComments(date) + governanceMapper.countPlaylistsCreated(date));
        addMetric(rows, date, "metric_ai_cost", governanceMapper.sumAiCostCent(date) / 100d);
        addMetric(rows, date, "metric_online_user", governanceMapper.countLateHourUsers(date));
        addMetric(rows, date, "metric_track_success_rate", percent(
                governanceMapper.countEvents(date),
                governanceMapper.countEvents(date) + governanceMapper.countDirty(date)));
        addMetric(rows, date, "metric_content_report_cnt", governanceMapper.countReports(date));
        addMetric(rows, date, "metric_data_quality_score", percent(
                governanceMapper.countQualityPassed(date), governanceMapper.countQualityRules(date)));
        addMetric(rows, date, "metric_funnel_activation", activationRate(date));

        if (!date.plusDays(1).isAfter(LocalDate.now())) {
            addMetric(rows, date, "metric_retention_d1", percent(
                    governanceMapper.countRetainedNewUsers(date, 1), newUsers));
        }
        if (!date.plusDays(7).isAfter(LocalDate.now())) {
            addMetric(rows, date, "metric_retention_d7", percent(
                    governanceMapper.countRetainedNewUsers(date, 7), newUsers));
        }

        // 分维度：DAU 与播放次数，供趋势图的堆叠/对比使用
        for (String dimension : DIMENSIONS) {
            for (DimValueRow row : analyticsMapper.drillActiveSubject(date, dimension)) {
                addMetricRow(rows, date, "metric_dau", dimension, row.getDimValue(), number(row.getV()));
            }
            for (DimValueRow row : analyticsMapper.drillEventCount("player_song_play", date, dimension)) {
                addMetricRow(rows, date, "metric_play_cnt", dimension, row.getDimValue(), number(row.getV()));
            }
        }
        return rows;
    }

    private double activationRate(LocalDate date) {
        try {
            FunnelReq req = new FunnelReq();
            req.setName("新用户激活漏斗");
            req.setStart(date.toString());
            req.setEnd(date.plusDays(6).toString());
            req.setWindowHours(168);
            List<FunnelReq.Step> steps = new ArrayList<>();
            steps.add(funnelStep("page_view", "打开首页", "page_id", "home", 1));
            steps.add(funnelStep("player_song_play", "播放第一首歌", null, null, 1));
            steps.add(funnelStep("player_valid_play", "播放 ≥ 3 首（有效）", null, null, 3));
            steps.add(funnelStep("page_view", "回访（7 日内）", null, null, 1));
            req.setSteps(steps);
            Object rate = funnelService.query(req).get("overallConversion");
            return rate instanceof Number ? ((Number) rate).doubleValue() : 0d;
        } catch (Exception e) {
            log.warn("activation funnel metric skipped date={} reason={}", date, e.getMessage());
            return 0d;
        }
    }

    private static FunnelReq.Step funnelStep(String eventName, String label, String filterKey,
                                             String filterValue, int minCount) {
        FunnelReq.Step step = new FunnelReq.Step();
        step.setEventName(eventName);
        step.setLabel(label);
        if (filterKey != null) {
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put(filterKey, filterValue);
            step.setFilters(filters);
        }
        step.setMinCount(minCount);
        return step;
    }

    private void addMetric(List<Map<String, Object>> rows, LocalDate date, String metricId, long value) {
        addMetricRow(rows, date, metricId, "ALL", "", BigDecimal.valueOf(value));
    }

    private void addMetric(List<Map<String, Object>> rows, LocalDate date, String metricId, double value) {
        addMetricRow(rows, date, metricId, "ALL", "", BigDecimal.valueOf(value));
    }

    /** percent() 返回 BigDecimal，保留 4 位小数的精度直接落库，避免再一次 double 往返 */
    private void addMetric(List<Map<String, Object>> rows, LocalDate date, String metricId, BigDecimal value) {
        addMetricRow(rows, date, metricId, "ALL", "", value);
    }

    private void addMetricRow(List<Map<String, Object>> rows, LocalDate date, String metricId,
                              String dimension, String dimensionValue, BigDecimal value) {
        String metricName = METRIC_NAMES.getOrDefault(metricId, metricId);
        BigDecimal normalized = value == null ? BigDecimal.ZERO
                : value.setScale(4, RoundingMode.HALF_UP);
        String dimValue = trim(dimensionValue);
        warehouseMapper.upsertAds(IdWorker.getId(), date, metricId, metricName, normalized,
                dimension, dimValue, null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("metricId", metricId);
        item.put("value", normalized.doubleValue());
        item.put("dimension", dimValue.isEmpty() ? "ALL" : dimValue);
        rows.add(item);
    }

    private static BigDecimal percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator * 100d / denominator);
    }

    private static BigDecimal number(Long value) {
        return BigDecimal.valueOf(value == null ? 0L : value);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
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
