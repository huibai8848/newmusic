package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.dto.CostRecordReq;
import com.example.newmusic.module.ops.entity.CostRecord;
import com.example.newmusic.module.ops.mapper.CostRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成本核算（FR-28-013 / AC-28-013-1~5）。
 *
 * <p>金额全部用「分」的整数存（{@code BIGINT}），避免浮点误差；展示层再除 100。
 * 每日预算来自 {@code t_cost_record.budget_cent} 的当日合计，没有记录时回退到配置中心
 * {@code ops.cost.daily-budget-cent}。预算比例 ≥ 0.8 时 {@code alert=true}，
 * 与 {@code ai_cost_budget} 告警规则同一阈值。
 */
@Slf4j
@Service
public class CostService {

    /** 以 SQL 种子的九类为准；后几项是给未来中间件预留的扩展分类。 */
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "CDN", "SERVER", "DATABASE", "REDIS", "STORAGE", "AI", "AUDIT", "SMS", "LOG",
            "DB", "CACHE", "MQ", "SEARCH", "MONITOR", "OTHER"));

    private final CostRecordMapper mapper;
    private final OpsConfigService configService;

    public CostService(CostRecordMapper mapper, OpsConfigService configService) {
        this.mapper = mapper;
        this.configService = configService;
    }

    public Map<String, Object> overview() {
        long todayAmount = mapper.sumToday();
        long todayBudget = mapper.budgetToday();
        if (todayBudget <= 0) {
            todayBudget = configService.getLong(OpsConfigService.KEY_COST_DAILY_BUDGET_CENT, 50000L);
        }
        double ratio = todayBudget <= 0 ? 0d : (double) todayAmount / todayBudget;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("currency", "CNY");
        map.put("unit", "cent");
        map.put("todayAmountCent", todayAmount);
        map.put("todayBudgetCent", todayBudget);
        map.put("todayRemainingCent", Math.max(0, todayBudget - todayAmount));
        map.put("todayUsageRatio", round(ratio));
        map.put("alert", ratio >= 0.8d);
        map.put("last7DaysCent", mapper.sumRecentDays(7));
        map.put("last30DaysCent", mapper.sumRecentDays(30));
        map.put("daily", mapper.dailyTotals(30));
        map.put("byCategory", mapper.byCategory(30));
        map.put("byService", mapper.byService(30));
        map.put("todayRecords", todayRecords());
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    public List<CostRecord> todayRecords() {
        return mapper.selectList(new LambdaQueryWrapper<CostRecord>()
                .eq(CostRecord::getCostDate, LocalDate.now())
                .orderByDesc(CostRecord::getAmountCent));
    }

    public List<CostRecord> records(int days, String category, int limit) {
        LambdaQueryWrapper<CostRecord> wrapper = new LambdaQueryWrapper<CostRecord>()
                .ge(CostRecord::getCostDate, LocalDate.now().minusDays(Math.max(1, Math.min(365, days))));
        if (StringUtils.hasText(category)) {
            wrapper.eq(CostRecord::getCategory, category.toUpperCase());
        }
        wrapper.orderByDesc(CostRecord::getCostDate).orderByDesc(CostRecord::getAmountCent)
                .last("LIMIT " + Math.max(1, Math.min(500, limit)));
        return mapper.selectList(wrapper);
    }

    public CostRecord add(CostRecordReq req) {
        String category = req.getCategory().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw new BizException(ResultCode.PARAM_INVALID, "未知成本分类 " + req.getCategory());
        }
        CostRecord record = new CostRecord();
        record.setCostDate(LocalDate.now());
        record.setCategory(category);
        record.setService(req.getService());
        record.setAmountCent(req.getAmountCent());
        record.setBudgetCent(req.getBudgetCent() == null ? 0L : req.getBudgetCent());
        record.setDetail(req.getDetail());
        record.setCreateTime(LocalDateTime.now());
        mapper.insert(record);
        log.info("成本记录新增 category={} service={} amountCent={}", category,
                req.getService(), req.getAmountCent());
        return record;
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
