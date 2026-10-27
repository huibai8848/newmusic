package com.example.newmusic.module.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ops.entity.CostRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CostRecordMapper extends BaseMapper<CostRecord> {

    @Select("SELECT COALESCE(SUM(amount_cent), 0) FROM newmusic.t_cost_record "
            + "WHERE cost_date = CURDATE()")
    long sumToday();

    @Select("SELECT COALESCE(SUM(budget_cent), 0) FROM newmusic.t_cost_record "
            + "WHERE cost_date = CURDATE()")
    long budgetToday();

    @Select("SELECT COALESCE(SUM(amount_cent), 0) FROM newmusic.t_cost_record "
            + "WHERE cost_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)")
    long sumRecentDays(@Param("days") int days);

    @Select("SELECT cost_date AS date, SUM(amount_cent) AS amount, SUM(budget_cent) AS budget "
            + "FROM newmusic.t_cost_record "
            + "WHERE cost_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) "
            + "GROUP BY cost_date ORDER BY cost_date")
    List<Map<String, Object>> dailyTotals(@Param("days") int days);

    @Select("SELECT category, SUM(amount_cent) AS amount FROM newmusic.t_cost_record "
            + "WHERE cost_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) "
            + "GROUP BY category ORDER BY amount DESC")
    List<Map<String, Object>> byCategory(@Param("days") int days);

    @Select("SELECT service, SUM(amount_cent) AS amount FROM newmusic.t_cost_record "
            + "WHERE cost_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) "
            + "GROUP BY service ORDER BY amount DESC")
    List<Map<String, Object>> byService(@Param("days") int days);
}
