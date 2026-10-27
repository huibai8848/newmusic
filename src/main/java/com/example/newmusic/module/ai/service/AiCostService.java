package com.example.newmusic.module.ai.service;

import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import com.example.newmusic.module.ai.entity.AiCallLog;
import com.example.newmusic.module.ai.mapper.AiCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 成本核算与调用日志（BR-23-003 / BR-23-011）。
 *
 * <p><b>成本以「分」向上取整，且每次成功调用至少 1 分。</b>四舍五入会让绝大多数单次调用
 * 记成 0 分（一次 SMALL 调用约 0.0003 元），于是成本报表永远是 0、预算永远触发不了——
 * 这比轻微高估更糟。向上取整保证「有调用就有账」，日预算的分母也才有意义。
 * 真实账单仍以供应商后台为准，这里的数字用于相对比较与熔断。
 */
@Slf4j
@Service
public class AiCostService {

    private final AiCallLogMapper logMapper;
    private final AiProperties properties;

    public AiCostService(AiCallLogMapper logMapper, AiProperties properties) {
        this.logMapper = logMapper;
        this.properties = properties;
    }

    public int estimateCostCent(AiScene.Level level, int inputTokens, int outputTokens) {
        AiProperties.Price price = properties.getPrice();
        double in;
        double out;
        switch (level) {
            case LARGE:
                in = price.getLargeInput();
                out = price.getLargeOutput();
                break;
            case MEDIUM:
                in = price.getMediumInput();
                out = price.getMediumOutput();
                break;
            default:
                in = price.getSmallInput();
                out = price.getSmallOutput();
                break;
        }
        double yuan = inputTokens / 1000d * in + outputTokens / 1000d * out;
        return Math.max(1, (int) Math.ceil(yuan * 100d));
    }

    /** 落一条调用日志。日志失败绝不能影响业务调用。 */
    public void record(AiCallLog entry) {
        try {
            if (entry.getCreateTime() == null) {
                entry.setCreateTime(LocalDateTime.now());
            }
            logMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("AI 调用日志写入失败 scene={} bizId={} cause={}",
                    entry.getScene(), entry.getBizId(), e.toString());
        }
    }

    public List<Map<String, Object>> sceneStats(LocalDate start, LocalDate end) {
        List<AiCallLogMapper.SceneStat> rows = logMapper.selectSceneStats(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (AiCallLogMapper.SceneStat row : rows) {
            long calls = nz(row.getCalls());
            long hits = nz(row.getCacheHits());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scene", row.getScene());
            item.put("calls", calls);
            item.put("cacheHits", hits);
            item.put("cacheHitRate", calls == 0 ? 0d : Math.round(hits * 1000d / calls) / 1000d);
            item.put("successCalls", nz(row.getSuccessCount()));
            item.put("degradedCalls", nz(row.getDegradedCount()));
            item.put("costCent", nz(row.getCostCent()));
            item.put("avgLatencyMs", nz(row.getAvgLatencyMs()));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> dailyStats(LocalDate start, LocalDate end) {
        List<AiCallLogMapper.DailyStat> rows = logMapper.selectDailyStats(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (AiCallLogMapper.DailyStat row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", row.getDay() == null ? null : row.getDay().toString());
            item.put("calls", nz(row.getCalls()));
            item.put("cacheHits", nz(row.getCacheHits()));
            item.put("costCent", nz(row.getCostCent()));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> overview(LocalDate start, LocalDate end) {
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("start", start.toString());
        view.put("end", end.toString());
        view.put("calls", logMapper.countBetween(from, to));
        view.put("costCent", logMapper.sumCostBetween(from, to));
        view.put("scenes", sceneStats(start, end));
        return view;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
