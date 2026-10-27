package com.example.newmusic.module.dataplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.dataplatform.entity.Reconciliation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface ReconciliationMapper extends BaseMapper<Reconciliation> {

    /** 按 (check_date, item) 幂等写入，重跑覆盖同一行（BR-26-093）。 */
    @Insert("INSERT INTO newmusic.t_reconciliation "
            + "(id, check_date, item, dw_value, biz_value, deviation, threshold, passed, create_time) "
            + "VALUES (#{id}, #{date}, #{item}, #{dwValue}, #{bizValue}, #{deviation}, #{threshold}, "
            + "#{passed}, NOW(3)) "
            + "ON DUPLICATE KEY UPDATE dw_value = VALUES(dw_value), biz_value = VALUES(biz_value), "
            + "deviation = VALUES(deviation), threshold = VALUES(threshold), passed = VALUES(passed)")
    int upsert(@Param("id") Long id, @Param("date") LocalDate date, @Param("item") String item,
               @Param("dwValue") long dwValue, @Param("bizValue") long bizValue,
               @Param("deviation") BigDecimal deviation, @Param("threshold") BigDecimal threshold,
               @Param("passed") int passed);
}
