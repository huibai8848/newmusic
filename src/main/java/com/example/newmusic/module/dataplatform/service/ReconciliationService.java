package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.entity.Reconciliation;
import com.example.newmusic.module.dataplatform.mapper.GovernanceQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.ReconciliationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数仓与业务库对账（FR-26-011 / BR-26-103 / 104）。
 *
 * <p>8 个对账项覆盖用户、播放、互动、内容与成本；偏差超阈值即 {@code passed=0} 并进入告警，
 * 不允许「忽略」。对账按 (date,item) upsert，重跑覆盖同一行。
 */
@Slf4j
@Service
public class ReconciliationService {

    private final ReconciliationMapper reconciliationMapper;
    private final GovernanceQueryMapper governanceMapper;

    public ReconciliationService(ReconciliationMapper reconciliationMapper,
                                 GovernanceQueryMapper governanceMapper) {
        this.reconciliationMapper = reconciliationMapper;
        this.governanceMapper = governanceMapper;
    }

    public Map<String, Object> run(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        if (date.isAfter(LocalDate.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能对账未来日期");
        }
        long begin = System.currentTimeMillis();
        List<Item> items = new ArrayList<>();
        items.add(item("new_user_cnt", metric(date, "metric_new_user_cnt"),
                governanceMapper.countUsersCreated(date), 0d));
        items.add(item("dau", metric(date, "metric_dau"),
                governanceMapper.countPlayUsers(date), 5d));
        items.add(item("play_cnt", metric(date, "metric_play_cnt"),
                governanceMapper.countPlayHistory(date), 1d));
        items.add(item("song_collect_cnt", governanceMapper.sumDwsSongField("collect_cnt", date),
                governanceMapper.countCollects(date), 1d));
        items.add(item("post_cnt", governanceMapper.sumDwsUserField("post_cnt", date),
                governanceMapper.countPosts(date), 0d));
        items.add(item("comment_cnt", governanceMapper.sumDwsUserField("comment_cnt", date),
                governanceMapper.countComments(date), 1d));
        items.add(item("room_play_cnt", governanceMapper.sumDwsUserField("room_cnt", date),
                governanceMapper.countRoomPlayLogs(date), 2d));
        items.add(item("ai_cost_cent",
                Math.round(metric(date, "metric_ai_cost") * 100d),
                governanceMapper.sumAiCostCent(date), 0d));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> alerts = new ArrayList<>();
        int passed = 0;
        for (Item item : items) {
            BigDecimal deviation = deviation(item.dwValue, item.bizValue);
            boolean ok = deviation.doubleValue() <= item.threshold;
            if (ok) {
                passed++;
            }
            BigDecimal threshold = BigDecimal.valueOf(item.threshold).setScale(4, RoundingMode.HALF_UP);
            reconciliationMapper.upsert(IdWorker.getId(), date, item.name, item.dwValue,
                    item.bizValue, deviation, threshold, ok ? 1 : 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", item.name);
            row.put("dwValue", item.dwValue);
            row.put("bizValue", item.bizValue);
            row.put("deviation", deviation.doubleValue());
            row.put("threshold", item.threshold);
            row.put("passed", ok);
            rows.add(row);
            if (!ok) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("item", item.name);
                alert.put("deviation", deviation.doubleValue());
                alert.put("threshold", item.threshold);
                alert.put("message", "对账偏差 " + deviation.doubleValue() + "% 超过阈值 " + item.threshold + "%");
                alerts.add(alert);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkDate", date.toString());
        result.put("total", items.size());
        result.put("passed", passed);
        result.put("failed", items.size() - passed);
        result.put("items", rows);
        result.put("alerts", alerts);
        result.put("costMs", System.currentTimeMillis() - begin);
        result.put("idempotent", true);
        return result;
    }

    public List<Map<String, Object>> list(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now().minusDays(1));
        List<Reconciliation> list = reconciliationMapper.selectList(
                Wrappers.<Reconciliation>lambdaQuery()
                        .eq(Reconciliation::getCheckDate, date)
                        .orderByAsc(Reconciliation::getItem));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Reconciliation item : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkDate", item.getCheckDate() == null ? null : item.getCheckDate().toString());
            row.put("item", item.getItem());
            row.put("dwValue", item.getDwValue());
            row.put("bizValue", item.getBizValue());
            row.put("deviation", item.getDeviation() == null ? null : item.getDeviation().doubleValue());
            row.put("threshold", item.getThreshold() == null ? null : item.getThreshold().doubleValue());
            row.put("passed", item.getPassed() != null && item.getPassed() == 1);
            rows.add(row);
        }
        return rows;
    }

    // ================================================================== 工具

    private double metric(LocalDate date, String metricId) {
        BigDecimal value = governanceMapper.adsMetric(date, metricId);
        return value == null ? 0d : value.doubleValue();
    }

    private static Item item(String name, double dwValue, long bizValue, double threshold) {
        return new Item(name, Math.round(dwValue), bizValue, threshold);
    }

    private static BigDecimal deviation(long dwValue, long bizValue) {
        if (bizValue == 0) {
            return dwValue == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100).setScale(4, RoundingMode.HALF_UP);
        }
        double value = Math.abs(dwValue - bizValue) * 100d / Math.abs(bizValue);
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
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

    private static class Item {
        final String name;
        final long dwValue;
        final long bizValue;
        final double threshold;

        Item(String name, long dwValue, long bizValue, double threshold) {
            this.name = name;
            this.dwValue = dwValue;
            this.bizValue = bizValue;
            this.threshold = threshold;
        }
    }
}
