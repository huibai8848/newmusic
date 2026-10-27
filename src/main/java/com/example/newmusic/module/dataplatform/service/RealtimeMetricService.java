package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.entity.AdsDailyMetrics;
import com.example.newmusic.module.dataplatform.mapper.AdsDailyMetricsMapper;
import com.example.newmusic.module.dataplatform.mapper.AnalyticsQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.GovernanceQueryMapper;
import com.example.newmusic.module.dataplatform.model.DayValueRow;
import com.example.newmusic.module.dataplatform.model.DimValueRow;
import com.example.newmusic.module.dataplatform.model.IdNameRow;
import com.example.newmusic.module.dataplatform.model.TopItemRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实时看板、趋势与下钻（FR-26-005）。
 *
 * <p>实时值优先读 Redis；Redis 没数（重启、跨天、采集计数失败）时回落到 ODS 现算，
 * 保证看板不会因为「缓存没了」显示 0。昨日对比优先用 ADS T+1，其次用昨日仍存活的 Redis 键。
 */
@Slf4j
@Service
public class RealtimeMetricService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, String> DIMENSION_COLUMNS = new HashMap<>();

    static {
        NAMES.put("metric_dau", "DAU");
        NAMES.put("metric_dau_play", "DAU-Play");
        NAMES.put("metric_play_cnt", "播放次数");
        NAMES.put("metric_valid_play_cnt", "有效播放");
        NAMES.put("metric_play_duration", "播放时长");
        NAMES.put("metric_avg_play_per_user", "人均播放次数");
        NAMES.put("metric_new_user_cnt", "新增用户");
        NAMES.put("metric_retention_d1", "次日留存");
        NAMES.put("metric_retention_d7", "7 日留存");
        NAMES.put("metric_completion_rate", "播放完成率");
        NAMES.put("metric_song_coverage", "曲库有效播放率");
        NAMES.put("metric_recommend_ctr", "推荐 CTR");
        NAMES.put("metric_search_success_rate", "搜索成功率");
        NAMES.put("metric_room_active_user", "房间用户");
        NAMES.put("metric_interaction_user", "互动用户");
        NAMES.put("metric_content_produce", "内容生产量");
        NAMES.put("metric_ai_cost", "AI 成本");
        NAMES.put("metric_online_user", "当前在线");
        NAMES.put("metric_track_success_rate", "埋点上报成功率");
        NAMES.put("metric_funnel_activation", "激活漏斗转化");
        NAMES.put("metric_content_report_cnt", "内容举报数");
        NAMES.put("metric_data_quality_score", "数据质量分");

        DIMENSION_COLUMNS.put("platform", "platform");
        DIMENSION_COLUMNS.put("os", "os");
        DIMENSION_COLUMNS.put("network", "network");
        DIMENSION_COLUMNS.put("region", "region");
        DIMENSION_COLUMNS.put("pageId", "page_id");
        DIMENSION_COLUMNS.put("appVersion", "app_version");
    }

    private final RedisOps redisOps;
    private final AnalyticsQueryMapper analyticsMapper;
    private final GovernanceQueryMapper governanceMapper;
    private final AdsDailyMetricsMapper adsMapper;
    private final DataPlatformProperties properties;

    public RealtimeMetricService(RedisOps redisOps, AnalyticsQueryMapper analyticsMapper,
                                 GovernanceQueryMapper governanceMapper,
                                 AdsDailyMetricsMapper adsMapper,
                                 DataPlatformProperties properties) {
        this.redisOps = redisOps;
        this.analyticsMapper = analyticsMapper;
        this.governanceMapper = governanceMapper;
        this.adsMapper = adsMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 触发一次 Redis 探测，避免首页第一个请求承担连接建立耗时
        redisOps.isAvailable();
    }

    // ================================================================== 实时看板

    public Map<String, Object> dashboard() {
        LocalDate today = LocalDate.now();
        String date = today.format(DAY);
        LocalDate yesterday = today.minusDays(1);
        boolean redisOk = redisOps.isAvailable();

        List<Map<String, Object>> cards = new ArrayList<>();
        addCard(cards, "metric_dau", "DAU", ratioOrNull(redisOps.pfCount(RedisKeys.stat("dau", date))),
                odsFallback("metric_dau", today), base("metric_dau", yesterday), "人");
        addCard(cards, "metric_dau_play", "DAU-Play",
                ratioOrNull(redisOps.pfCount(RedisKeys.stat("dau_play", date))),
                odsFallback("metric_dau_play", today), base("metric_dau_play", yesterday), "人");
        addCard(cards, "metric_play_cnt", "播放次数", redisNumber(RedisKeys.stat("play_cnt", date)),
                odsFallback("metric_play_cnt", today), base("metric_play_cnt", yesterday), "次");
        addCard(cards, "metric_valid_play_cnt", "有效播放",
                redisNumber(RedisKeys.stat("valid_play_cnt", date)),
                odsFallback("metric_valid_play_cnt", today), base("metric_valid_play_cnt", yesterday), "次");
        addCard(cards, "metric_online_user", "当前在线", onlineCount(date), null, null, "人");
        addCard(cards, "metric_new_user_cnt", "新增用户", redisNumber(RedisKeys.stat("new_user_cnt", date)),
                odsFallback("metric_new_user_cnt", today), base("metric_new_user_cnt", yesterday), "人");
        addCard(cards, "metric_room_active_user", "房间用户",
                ratioOrNull(redisOps.pfCount(RedisKeys.stat("room_user", date))),
                odsFallback("metric_room_active_user", today), base("metric_room_active_user", yesterday), "人");
        addCard(cards, "metric_interaction_user", "互动用户",
                ratioOrNull(redisOps.pfCount(RedisKeys.stat("interaction_user", date))),
                odsFallback("metric_interaction_user", today), base("metric_interaction_user", yesterday), "人");
        addCard(cards, "metric_ai_cost", "AI 成本",
                divide(redisNumber(RedisKeys.stat("ai_cost_cent", date)), 100d),
                odsFallback("metric_ai_cost", today), base("metric_ai_cost", yesterday), "元");

        Double recommendCtr = percent(redisNumber(RedisKeys.stat("recommend_click", date)),
                redisNumber(RedisKeys.stat("recommend_exposure", date)));
        if (recommendCtr == null) {
            recommendCtr = odsFallback("metric_recommend_ctr", today);
        }
        addCard(cards, "metric_recommend_ctr", "推荐 CTR", recommendCtr, null,
                base("metric_recommend_ctr", yesterday), "%");

        Double searchRate = percent(redisNumber(RedisKeys.stat("search_result_click", date)),
                redisNumber(RedisKeys.stat("search_submit", date)));
        if (searchRate == null) {
            searchRate = odsFallback("metric_search_success_rate", today);
        }
        addCard(cards, "metric_search_success_rate", "搜索成功率", searchRate, null,
                base("metric_search_success_rate", yesterday), "%");

        Map<String, String> ingest = redisOps.hashGetAll(RedisKeys.trackIngest(date));
        Double successRate = percent(parse(ingest.get("accepted")), parse(ingest.get("received")));
        addCard(cards, "metric_track_success_rate", "埋点上报成功率", successRate, null,
                base("metric_track_success_rate", yesterday), "%");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("updatedAt", LocalDateTime.now().withNano(0).toString());
        result.put("delaySeconds", delaySeconds(ingest));
        result.put("redisAvailable", redisOk);
        result.put("cards", cards);
        result.put("ingest", ingest);
        result.put("topSongs", topSongs(today, date));
        result.put("topRooms", topRooms(today, date));
        result.put("compareNote", "环比基准为昨日整日（ADS T+1，缺失时用昨日实时键）");
        return result;
    }

    // ================================================================== 趋势

    public Map<String, Object> trend(String metricId, String start, String end, String dimension) {
        LocalDate endDate = parseDate(end, LocalDate.now());
        LocalDate startDate = parseDate(start, endDate.minusDays(29));
        if (startDate.isAfter(endDate)) {
            LocalDate tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > 180) {
            throw new BizException(ResultCode.PARAM_INVALID, "趋势查询区间不能超过 180 天");
        }

        List<Map<String, Object>> points = new ArrayList<>();
        List<Map<String, Object>> series = new ArrayList<>();
        String source = "ADS";

        if (dimension != null && !dimension.trim().isEmpty()) {
            String dim = dimension.trim();
            List<AdsDailyMetrics> rows = adsMapper.selectList(Wrappers.<AdsDailyMetrics>lambdaQuery()
                    .eq(AdsDailyMetrics::getMetricId, metricId)
                    .eq(AdsDailyMetrics::getDimension, dim)
                    .between(AdsDailyMetrics::getStatDate, startDate, endDate)
                    .orderByAsc(AdsDailyMetrics::getStatDate));
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (AdsDailyMetrics row : rows) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", row.getStatDate().toString());
                point.put("value", round(row.getMetricValue()));
                point.put("dimensionValue", row.getDimensionValue());
                points.add(point);
                grouped.computeIfAbsent(row.getDimensionValue(), k -> new ArrayList<>()).add(point);
            }
            for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("dimensionValue", entry.getKey());
                item.put("points", entry.getValue());
                series.add(item);
            }
        } else {
            Map<LocalDate, Double> ads = new LinkedHashMap<>();
            List<AdsDailyMetrics> rows = adsMapper.selectList(Wrappers.<AdsDailyMetrics>lambdaQuery()
                    .eq(AdsDailyMetrics::getMetricId, metricId)
                    .eq(AdsDailyMetrics::getDimension, "ALL")
                    .between(AdsDailyMetrics::getStatDate, startDate, endDate)
                    .orderByAsc(AdsDailyMetrics::getStatDate));
            for (AdsDailyMetrics row : rows) {
                ads.put(row.getStatDate(), row.getMetricValue() == null ? 0d : row.getMetricValue().doubleValue());
            }
            Map<LocalDate, Double> ods = odsSeries(metricId, startDate, endDate);
            for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                Double value = ads.get(d);
                if (value == null) {
                    value = ods.get(d);
                    if (value != null) {
                        source = "ADS+ODS";
                    }
                }
                if (value == null) {
                    continue;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", d.toString());
                point.put("value", round(value));
                points.add(point);
            }
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("dimensionValue", "ALL");
            all.put("points", points);
            series.add(all);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);
        result.put("metricName", NAMES.getOrDefault(metricId, metricId));
        result.put("start", startDate.toString());
        result.put("end", endDate.toString());
        result.put("dimension", dimension);
        result.put("source", source);
        result.put("points", points);
        result.put("series", series);
        return result;
    }

    // ================================================================== 下钻

    public Map<String, Object> drill(String metricId, String dimension, String value, String dateText) {
        String dim = dimension == null ? "platform" : dimension.trim();
        String column = DIMENSION_COLUMNS.get(dim);
        if (column == null) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "不支持的维度，可用：" + DIMENSION_COLUMNS.keySet());
        }
        LocalDate date = parseDate(dateText, LocalDate.now());
        List<DimValueRow> rows = drillRows(metricId, date, column);
        List<Map<String, Object>> values = new ArrayList<>();
        long total = 0;
        for (DimValueRow row : rows) {
            if (value != null && !value.trim().isEmpty() && !value.equals(row.getDimValue())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dimensionValue", row.getDimValue());
            item.put("value", row.getV() == null ? 0 : row.getV());
            values.add(item);
            total += row.getV() == null ? 0 : row.getV();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);
        result.put("metricName", NAMES.getOrDefault(metricId, metricId));
        result.put("date", date.toString());
        result.put("dimension", dim);
        result.put("total", total);
        result.put("values", values);
        return result;
    }

    // ================================================================== 数据源适配

    private List<DimValueRow> drillRows(String metricId, LocalDate date, String column) {
        int validSeconds = properties.getValidPlaySeconds();
        if ("metric_dau".equals(metricId) || "metric_online_user".equals(metricId)) {
            return analyticsMapper.drillActiveSubject(date, column);
        }
        if ("metric_play_cnt".equals(metricId)) {
            return analyticsMapper.drillEventCount("player_song_play", date, column);
        }
        if ("metric_valid_play_cnt".equals(metricId)) {
            return analyticsMapper.drillValidPlay(date, column, validSeconds);
        }
        if ("metric_new_user_cnt".equals(metricId)) {
            return analyticsMapper.drillEventCount("user_register_success", date, column);
        }
        if ("metric_room_active_user".equals(metricId)) {
            return analyticsMapper.drillDistinctUserByEvent("room_enter_success", date, column);
        }
        if ("metric_search_success_rate".equals(metricId)) {
            return analyticsMapper.drillEventCount("search_submit", date, column);
        }
        if ("metric_recommend_ctr".equals(metricId)) {
            return analyticsMapper.drillEventCount("recommend_exposure", date, column);
        }
        return Collections.emptyList();
    }

    /**
     * ODS 现算的按天序列。只覆盖「用埋点就能算出来」的指标；其余指标依赖 ADS。
     */
    private Map<LocalDate, Double> odsSeries(String metricId, LocalDate start, LocalDate end) {
        int validSeconds = properties.getValidPlaySeconds();
        Map<LocalDate, Double> result = new LinkedHashMap<>();
        if ("metric_dau".equals(metricId)) {
            fill(result, analyticsMapper.activeSubjectByDay(start, end));
        } else if ("metric_play_cnt".equals(metricId)) {
            fill(result, analyticsMapper.eventCountByDay("player_song_play", start, end));
        } else if ("metric_valid_play_cnt".equals(metricId)) {
            fill(result, analyticsMapper.validPlayByDay(start, end, validSeconds));
        } else if ("metric_dau_play".equals(metricId)) {
            fill(result, analyticsMapper.dauPlayByDay(start, end, validSeconds,
                    properties.getDauPlayThreshold()));
        } else if ("metric_new_user_cnt".equals(metricId)) {
            fill(result, analyticsMapper.eventCountByDay("user_register_success", start, end));
        } else if ("metric_room_active_user".equals(metricId)) {
            fill(result, analyticsMapper.distinctUserByEventByDay("room_enter_success", start, end));
        } else if ("metric_interaction_user".equals(metricId)) {
            fill(result, analyticsMapper.interactionUsersByDay(start, end));
        } else if ("metric_play_duration".equals(metricId)) {
            fill(result, analyticsMapper.sumPropByDay("player_song_end", "play_duration", start, end));
        } else if ("metric_ai_cost".equals(metricId)) {
            Map<LocalDate, Double> cents = new LinkedHashMap<>();
            fill(cents, analyticsMapper.sumPropByDay("ai_call", "cost_cent", start, end));
            cents.forEach((d, v) -> result.put(d, divide(v, 100d)));
        } else if ("metric_content_produce".equals(metricId)) {
            fill(result, analyticsMapper.eventCountInByDay(
                    Arrays.asList("post_publish_success", "post_comment_submit", "playlist_collect_click"),
                    start, end));
        } else if ("metric_recommend_ctr".equals(metricId)) {
            ratioSeries(result, analyticsMapper.eventCountByDay("recommend_click", start, end),
                    analyticsMapper.eventCountByDay("recommend_exposure", start, end));
        } else if ("metric_search_success_rate".equals(metricId)) {
            ratioSeries(result, analyticsMapper.eventCountByDay("search_result_click", start, end),
                    analyticsMapper.eventCountByDay("search_submit", start, end));
        }
        return result;
    }

    // ================================================================== 单项取值

    private Double odsFallback(String metricId, LocalDate date) {
        Map<LocalDate, Double> series = odsSeries(metricId, date, date);
        return series.get(date);
    }

    private Double base(String metricId, LocalDate date) {
        BigDecimal ads = governanceMapper.adsMetric(date, metricId);
        if (ads != null) {
            return ads.doubleValue();
        }
        Map<LocalDate, Double> ods = odsSeries(metricId, date, date);
        if (ods.containsKey(date)) {
            return ods.get(date);
        }
        // 今日的实时键在昨日键上同名不同日期；回落到昨日 Redis 计数
        String key = date.format(DAY);
        if ("metric_dau".equals(metricId)) {
            return ratioOrNull(redisOps.pfCount(RedisKeys.stat("dau", key)));
        }
        if ("metric_dau_play".equals(metricId)) {
            return ratioOrNull(redisOps.pfCount(RedisKeys.stat("dau_play", key)));
        }
        if ("metric_play_cnt".equals(metricId)) {
            return redisNumber(RedisKeys.stat("play_cnt", key));
        }
        if ("metric_valid_play_cnt".equals(metricId)) {
            return redisNumber(RedisKeys.stat("valid_play_cnt", key));
        }
        if ("metric_new_user_cnt".equals(metricId)) {
            return redisNumber(RedisKeys.stat("new_user_cnt", key));
        }
        if ("metric_room_active_user".equals(metricId)) {
            return ratioOrNull(redisOps.pfCount(RedisKeys.stat("room_user", key)));
        }
        if ("metric_interaction_user".equals(metricId)) {
            return ratioOrNull(redisOps.pfCount(RedisKeys.stat("interaction_user", key)));
        }
        if ("metric_ai_cost".equals(metricId)) {
            return divide(redisNumber(RedisKeys.stat("ai_cost_cent", key)), 100d);
        }
        return null;
    }

    private Double onlineCount(String date) {
        long now = System.currentTimeMillis() / 1000;
        return (double) redisOps.zCount(RedisKeys.stat("online", date), now - 300d,
                Double.POSITIVE_INFINITY);
    }

    private List<Map<String, Object>> topSongs(LocalDate today, String date) {
        Map<String, Double> scores = redisOps.zRevRangeWithScores(RedisKeys.stat("song:play", date), 0, 9);
        List<Map<String, Object>> result = new ArrayList<>();
        if (scores.isEmpty()) {
            for (TopItemRow row : governanceMapper.topSongsFromHistory(today, 10)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("songId", row.getId());
                item.put("name", row.getName());
                item.put("playCnt", row.getCnt());
                result.add(item);
            }
            return result;
        }
        List<Long> ids = new ArrayList<>();
        for (String key : scores.keySet()) {
            try {
                ids.add(Long.parseLong(key));
            } catch (NumberFormatException ignored) {
                // 非数字 songId 直接跳过，不影响其它条目
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (IdNameRow row : governanceMapper.songNames(ids)) {
                names.put(row.getId(), row.getName());
            }
        }
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            long songId;
            try {
                songId = Long.parseLong(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("songId", songId);
            item.put("name", names.getOrDefault(songId, "歌曲 " + songId));
            item.put("playCnt", entry.getValue() == null ? 0 : entry.getValue().longValue());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> topRooms(LocalDate today, String date) {
        Map<String, Double> scores = redisOps.zRevRangeWithScores(RedisKeys.stat("room_active", date), 0, 4);
        List<Map<String, Object>> result = new ArrayList<>();
        if (scores.isEmpty()) {
            for (TopItemRow row : governanceMapper.topRoomsFromMessages(today, 5)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("roomId", row.getId());
                item.put("name", row.getName());
                item.put("messageCnt", row.getCnt());
                result.add(item);
            }
            return result;
        }
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("roomId", entry.getKey());
            item.put("enterCnt", entry.getValue() == null ? 0 : entry.getValue().longValue());
            result.add(item);
        }
        return result;
    }

    // ================================================================== 卡片与工具

    private void addCard(List<Map<String, Object>> cards, String metricId, String name,
                         Double realtime, Double odsFallback, Double base, String unit) {
        Double value = realtime;
        String valueSource = "realtime";
        if ((value == null || value == 0d) && odsFallback != null) {
            value = odsFallback;
            valueSource = "ods";
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("metricId", metricId);
        card.put("name", name);
        card.put("value", round(value));
        card.put("unit", unit);
        card.put("source", valueSource);
        card.put("available", value != null);
        if (value != null && base != null && base != 0d) {
            double delta = value - base;
            card.put("base", round(base));
            card.put("delta", round(delta));
            card.put("deltaPct", round(delta / Math.abs(base) * 100d));
            card.put("direction", delta > 0 ? "up" : (delta < 0 ? "down" : "flat"));
            double pct = delta / Math.abs(base) * 100d;
            card.put("status", pct <= -20d ? "danger" : (pct >= 50d ? "success" : "normal"));
        } else {
            card.put("base", null);
            card.put("delta", null);
            card.put("deltaPct", null);
            card.put("direction", "flat");
            card.put("status", "normal");
        }
        cards.add(card);
    }

    private Long delaySeconds(Map<String, String> ingest) {
        String last = ingest.get("last_ts");
        if (last == null) {
            return null;
        }
        try {
            return Math.max(0, (System.currentTimeMillis() - Long.parseLong(last)) / 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void fill(Map<LocalDate, Double> target, List<DayValueRow> rows) {
        if (rows == null) {
            return;
        }
        for (DayValueRow row : rows) {
            if (row.getDt() != null) {
                target.put(row.getDt(), row.getV() == null ? 0d : row.getV().doubleValue());
            }
        }
    }

    private static void ratioSeries(Map<LocalDate, Double> target, List<DayValueRow> numerator,
                                    List<DayValueRow> denominator) {
        Map<LocalDate, Double> num = new LinkedHashMap<>();
        Map<LocalDate, Double> den = new LinkedHashMap<>();
        fill(num, numerator);
        fill(den, denominator);
        for (Map.Entry<LocalDate, Double> entry : den.entrySet()) {
            Double n = num.get(entry.getKey());
            if (n == null || entry.getValue() == null || entry.getValue() == 0d) {
                continue;
            }
            target.put(entry.getKey(), n / entry.getValue() * 100d);
        }
    }

    private Double redisNumber(String key) {
        Optional<String> raw = redisOps.get(key);
        if (!raw.isPresent()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.get());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double ratioOrNull(long count) {
        return count > 0 ? (double) count : null;
    }

    private static Double percent(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0d) {
            return null;
        }
        return numerator / denominator * 100d;
    }

    private static Double divide(Double value, double divisor) {
        return value == null ? null : value / divisor;
    }

    private static Double parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double round(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).doubleValue();
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
