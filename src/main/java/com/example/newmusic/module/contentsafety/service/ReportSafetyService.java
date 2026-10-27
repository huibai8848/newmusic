package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.entity.Report;
import com.example.newmusic.module.admin.mapper.ReportMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 举报治理（FR-27-006）：统计、监管转办、SLA 巡检。
 *
 * <p>举报的提交、合并与人工处置仍由模块 09/10 的既有服务负责；
 * 本类只补模块 18 新增的统计与时限能力，避免出现第二套举报状态机。
 */
@Slf4j
@Service
public class ReportSafetyService {

    private final ReportMapper reportMapper;

    public ReportSafetyService(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public Map<String, Object> stats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 90)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", since);
        out.put("total", reportMapper.selectCount(new QueryWrapper<Report>()
                .ge("create_time", since)));
        out.put("pending", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_PENDING)));
        out.put("valid", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_VALID).ge("create_time", since)));
        out.put("invalid", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_INVALID).ge("create_time", since)));
        out.put("autoHidden", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("auto_hidden", 1).ge("create_time", since)));
        out.put("regulator", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("is_regulator", 1).ge("create_time", since)));
        out.put("overdue", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_PENDING)
                .isNotNull("sla_deadline").lt("sla_deadline", LocalDateTime.now())));
        List<Map<String, Object>> byTarget = reportMapper.selectMaps(new QueryWrapper<Report>()
                .select("target_type", "COUNT(*) AS cnt").ge("create_time", since)
                .groupBy("target_type").orderByDesc("cnt"));
        out.put("byTargetType", byTarget);
        List<Map<String, Object>> byCategory = reportMapper.selectMaps(new QueryWrapper<Report>()
                .select("reason_category", "COUNT(*) AS cnt").ge("create_time", since)
                .groupBy("reason_category").orderByDesc("cnt"));
        out.put("byReasonCategory", byCategory);
        out.put("sla", slaStats());
        return out;
    }

    public Map<String, Object> slaStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overdue", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_PENDING)
                .isNotNull("sla_deadline").lt("sla_deadline", LocalDateTime.now())));
        out.put("dueSoon", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_PENDING)
                .isNotNull("sla_deadline")
                .between("sla_deadline", LocalDateTime.now(),
                        LocalDateTime.now().plusHours(1))));
        out.put("highPriorityPending", reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("status", Report.STATUS_PENDING)
                .eq("priority", Report.PRIORITY_HIGH)));
        return out;
    }

    /** 监管转办：优先级最高 + 2 小时 SLA（BR-27-070）。 */
    @Transactional
    public Report markRegulator(Long reportId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ResultCode.REPORT_NOT_FOUND);
        }
        if (!Integer.valueOf(Report.STATUS_PENDING).equals(report.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅待处理举报可转办");
        }
        reportMapper.markRegulator(reportId, LocalDateTime.now().plusHours(2));
        log.warn("监管转办举报 reportId={} target={}:{}", reportId,
                report.getTargetType(), report.getTargetId());
        return reportMapper.selectById(reportId);
    }

    /** 定时任务：给历史举报补齐 SLA，并返回已超时数量（由调用方告警）。 */
    public int backfillAndCountOverdue() {
        reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Report>()
                .eq("status", Report.STATUS_PENDING).isNull("sla_deadline")
                .setSql("sla_deadline = DATE_ADD(create_time, INTERVAL 24 HOUR)"));
        int overdue = reportMapper.selectSlaOverdue().size();
        if (overdue > 0) {
            log.warn("举报 SLA 已超时 {} 条，请优先处理", overdue);
        }
        return overdue;
    }
}
