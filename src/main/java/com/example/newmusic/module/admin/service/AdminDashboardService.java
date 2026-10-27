package com.example.newmusic.module.admin.service;

import com.example.newmusic.module.admin.mapper.DashboardMapper;
import com.example.newmusic.module.admin.vo.DashboardVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营看板。指标全部来自真实表聚合；近 7 天趋势会补齐没有数据的日期为 0，
 * 避免前端把「当天没有新增」误读成「图表断线」。
 */
@Service
public class AdminDashboardService {

    private static final int TREND_DAYS = 7;

    private final DashboardMapper dashboardMapper;

    public AdminDashboardService(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    public DashboardVO overview() {
        DashboardVO vo = dashboardMapper.summary();
        if (vo == null) {
            vo = new DashboardVO();
        }
        vo.setUserTrend(fillMissingDays(dashboardMapper.userTrend()));
        vo.setPlayTrend(fillMissingDays(dashboardMapper.playTrend()));
        return vo;
    }

    private List<DashboardVO.TrendPoint> fillMissingDays(List<DashboardVO.TrendPoint> rows) {
        Map<String, Long> byDate = new HashMap<>();
        if (rows != null) {
            for (DashboardVO.TrendPoint row : rows) {
                byDate.put(row.getDate(), row.getValue());
            }
        }
        java.util.ArrayList<DashboardVO.TrendPoint> result = new java.util.ArrayList<>(TREND_DAYS);
        LocalDate today = LocalDate.now();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            result.add(new DashboardVO.TrendPoint(date, byDate.getOrDefault(date, 0L)));
        }
        return result;
    }
}
