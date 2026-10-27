package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.entity.DataQualityRecord;
import com.example.newmusic.module.dataplatform.mapper.DataQualityRecordMapper;
import com.example.newmusic.module.dataplatform.mapper.GovernanceQueryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量校验（FR-26-011）。
 *
 * <p>覆盖 7 类规则：完整性（row_count）、唯一性（duplicate）、有效性（value_range）、
 * 及时性（freshness）、一致性（consistent）、准确性（cross_table）、波动性（anomaly）。
 * 每次按日期重跑会先删当日记录再写，保证幂等（BR-26-093）。
 *
 * <p>critical 规则失败即 {@code blocked=true}，下游报表必须展示「数据可能不准确」（BR-26-102/109）。
 */
@Slf4j
@Service
public class DataQualityService {

    private static final String CRITICAL = "critical";
    private static final String WARNING = "warning";

    private final DataQualityRecordMapper recordMapper;
    private final GovernanceQueryMapper governanceMapper;

    public DataQualityService(DataQualityRecordMapper recordMapper, GovernanceQueryMapper governanceMapper) {
        this.recordMapper = recordMapper;
        this.governanceMapper = governanceMapper;
    }

    // ================================================================== 执行

    public Map<String, Object> run(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        if (date.isAfter(LocalDate.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能校验未来日期");
        }
        long begin = System.currentTimeMillis();
        recordMapper.delete(Wrappers.<DataQualityRecord>lambdaQuery()
                .eq(DataQualityRecord::getCheckDate, date));

        List<DataQualityRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // ---- ODS: t_track_event
        long events = governanceMapper.countEvents(date);
        long distinctEvents = governanceMapper.countDistinctEventIds(date);
        long nullSubjects = governanceMapper.countNullSubjects(date);
        add(records, date, now, "t_track_event", "row_count",
                "当日行数 > 0", events > 0, String.valueOf(events), CRITICAL);
        add(records, date, now, "t_track_event", "duplicate",
                "event_id 重复数 = 0", events == distinctEvents,
                "重复 " + (events - distinctEvents) + " 条", CRITICAL);
        double nullRate = events == 0 ? 0d : nullSubjects * 100d / events;
        add(records, date, now, "t_track_event", "null_rate",
                "user_id/device_id 同时为空 < 1%", nullRate < 1d,
                round(nullRate) + "%", CRITICAL);
        LocalDateTime maxServerTime = governanceMapper.maxServerTime(date);
        boolean fresh;
        String freshnessActual;
        if (maxServerTime == null) {
            fresh = false;
            freshnessActual = "无数据";
        } else if (date.isBefore(LocalDate.now())) {
            fresh = true;
            freshnessActual = "已封盘，最后一条 " + maxServerTime;
        } else {
            long seconds = Duration.between(maxServerTime, now).getSeconds();
            fresh = seconds <= 6 * 3600;
            freshnessActual = "最后一条延迟 " + seconds + "s";
        }
        add(records, date, now, "t_track_event", "freshness",
                "当日最后一条埋点延迟 <= 6h", fresh, freshnessActual, CRITICAL);
        double previousAvg = governanceMapper.avgEventCount(date.minusDays(7), date.minusDays(1));
        boolean stable = previousAvg <= 0 || events == 0
                || Math.abs(events - previousAvg) / previousAvg <= 0.3d
                || Math.abs(events - previousAvg) <= 10;
        add(records, date, now, "t_track_event", "anomaly",
                "行数环比波动 <= 30%", stable,
                "今日 " + events + " / 前 7 日均值 " + round(previousAvg), WARNING);

        // ---- DWD: t_dwd_play_detail
        long dwd = governanceMapper.countDwd(date);
        long duplicates = governanceMapper.duplicateDwdEventIds(date);
        add(records, date, now, "t_dwd_play_detail", "row_count",
                "当日行数 > 0", dwd > 0, String.valueOf(dwd), CRITICAL);
        add(records, date, now, "t_dwd_play_detail", "duplicate",
                "event_id 重复数 = 0", duplicates == 0, "重复 " + duplicates + " 条", CRITICAL);
        int minDuration = governanceMapper.minPlayDuration(date);
        int maxDuration = governanceMapper.maxPlayDuration(date);
        add(records, date, now, "t_dwd_play_detail", "value_range",
                "play_duration ∈ [0, 86400]", minDuration >= 0 && maxDuration <= 86400,
                "[" + minDuration + ", " + maxDuration + "]", WARNING);
        BigDecimal minCompletion = governanceMapper.minCompletionRate(date);
        BigDecimal maxCompletion = governanceMapper.maxCompletionRate(date);
        add(records, date, now, "t_dwd_play_detail", "value_range",
                "completion_rate ∈ [0, 1]",
                minCompletion.compareTo(BigDecimal.ZERO) >= 0
                        && maxCompletion.compareTo(BigDecimal.ONE) <= 0,
                "[" + minCompletion + ", " + maxCompletion + "]", WARNING);
        long dwsPlayCnt = governanceMapper.sumDwsUserField("play_cnt", date);
        add(records, date, now, "t_dws_user_daily", "consistent",
                "DWS 播放次数之和 = DWD 行数", dwsPlayCnt == dwd,
                "DWS " + dwsPlayCnt + " / DWD " + dwd, CRITICAL);

        // ---- DWS
        long dwsUsers = governanceMapper.countDwsUser(date);
        long dwsSongs = governanceMapper.countDwsSong(date);
        add(records, date, now, "t_dws_user_daily", "row_count",
                "当日行数 > 0", dwsUsers > 0, String.valueOf(dwsUsers), WARNING);
        long dwsValid = governanceMapper.sumDwsUserField("valid_play_cnt", date);
        add(records, date, now, "t_dws_user_daily", "value_range",
                "valid_play_cnt <= play_cnt", dwsValid <= dwsPlayCnt,
                dwsValid + " / " + dwsPlayCnt, WARNING);
        add(records, date, now, "t_dws_song_daily", "row_count",
                "当日行数 > 0", dwsSongs > 0, String.valueOf(dwsSongs), WARNING);
        long songValid = governanceMapper.sumDwsSongField("valid_play_cnt", date);
        long songPlay = governanceMapper.sumDwsSongField("play_cnt", date);
        add(records, date, now, "t_dws_song_daily", "value_range",
                "valid_play_cnt <= play_cnt", songValid <= songPlay,
                songValid + " / " + songPlay, WARNING);

        // ---- ADS
        long ads = governanceMapper.countAds(date);
        add(records, date, now, "t_ads_daily_metrics", "row_count",
                "当日指标行数 > 0", ads > 0, String.valueOf(ads), WARNING);
        BigDecimal dauAds = governanceMapper.adsMetric(date, "metric_dau");
        add(records, date, now, "t_ads_daily_metrics", "value_range",
                "metric_dau >= 0", dauAds == null || dauAds.compareTo(BigDecimal.ZERO) >= 0,
                String.valueOf(dauAds), WARNING);

        int passed = 0;
        int criticalFailed = 0;
        List<Map<String, Object>> issues = new ArrayList<>();
        for (DataQualityRecord record : records) {
            recordMapper.insert(record);
            if (record.getPassed() != null && record.getPassed() == 1) {
                passed++;
            } else {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("table", record.getTableName());
                issue.put("ruleType", record.getRuleType());
                issue.put("ruleExpr", record.getRuleExpr());
                issue.put("actualValue", record.getActualValue());
                issue.put("severity", record.getSeverity());
                issues.add(issue);
                if (CRITICAL.equals(record.getSeverity())) {
                    criticalFailed++;
                }
            }
        }
        double score = records.isEmpty() ? 100d : round(passed * 100d / records.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkDate", date.toString());
        result.put("total", records.size());
        result.put("passed", passed);
        result.put("failed", records.size() - passed);
        result.put("score", score);
        result.put("blocked", criticalFailed > 0);
        result.put("criticalFailed", criticalFailed);
        result.put("health", health(score, criticalFailed));
        result.put("issues", issues);
        result.put("checkTime", now.withNano(0).toString());
        result.put("costMs", System.currentTimeMillis() - begin);
        result.put("idempotent", true);
        return result;
    }

    // ================================================================== 查询

    public Map<String, Object> report(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        List<DataQualityRecord> records = recordMapper.selectList(
                Wrappers.<DataQualityRecord>lambdaQuery()
                        .eq(DataQualityRecord::getCheckDate, date)
                        .orderByAsc(DataQualityRecord::getTableName, DataQualityRecord::getRuleType));
        int passed = 0;
        int criticalFailed = 0;
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (DataQualityRecord record : records) {
            boolean ok = record.getPassed() != null && record.getPassed() == 1;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", record.getTableName());
            item.put("ruleType", record.getRuleType());
            item.put("ruleExpr", record.getRuleExpr());
            item.put("passed", ok);
            item.put("actualValue", record.getActualValue());
            item.put("severity", record.getSeverity());
            item.put("checkTime", record.getCheckTime() == null ? null : record.getCheckTime().toString());
            items.add(item);
            if (ok) {
                passed++;
            } else {
                issues.add(item);
                if (CRITICAL.equals(record.getSeverity())) {
                    criticalFailed++;
                }
            }
        }
        double score = records.isEmpty() ? 100d : round(passed * 100d / records.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkDate", date.toString());
        result.put("total", records.size());
        result.put("passed", passed);
        result.put("failed", records.size() - passed);
        result.put("score", score);
        result.put("blocked", criticalFailed > 0);
        result.put("criticalFailed", criticalFailed);
        result.put("health", records.isEmpty() ? "unknown" : health(score, criticalFailed));
        result.put("items", items);
        result.put("issues", issues);
        return result;
    }

    // ================================================================== 工具

    private static void add(List<DataQualityRecord> records, LocalDate date, LocalDateTime now,
                            String table, String ruleType, String expr, boolean passed,
                            String actual, String severity) {
        DataQualityRecord record = new DataQualityRecord();
        record.setId(IdWorker.getId());
        record.setCheckDate(date);
        record.setTableName(table);
        record.setPartitionVal("dt=" + date);
        record.setRuleType(ruleType);
        record.setRuleExpr(expr);
        record.setPassed(passed ? 1 : 0);
        record.setActualValue(actual == null ? null
                : (actual.length() > 200 ? actual.substring(0, 200) : actual));
        record.setSeverity(severity);
        record.setCheckTime(now);
        records.add(record);
    }

    private static String health(double score, int criticalFailed) {
        if (criticalFailed > 0) {
            return "blocked";
        }
        if (score >= 95d) {
            return "healthy";
        }
        return score >= 80d ? "warning" : "degraded";
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
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
