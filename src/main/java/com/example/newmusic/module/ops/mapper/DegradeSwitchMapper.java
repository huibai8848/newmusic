package com.example.newmusic.module.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ops.entity.DegradeSwitch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DegradeSwitchMapper extends BaseMapper<DegradeSwitch> {

    /**
     * 一键降级拆成两条带明确条件的 UPDATE。
     *
     * <p>原来用 {@code SET enabled = IF(#{level} >= degrade_level, 0, 1)} + 同款 WHERE 时，
     * MyBatis-Plus 的 {@code BlockAttackInnerInterceptor} 会把这条语句判成「全表更新」直接拒绝
     * （{@code Prohibition of table update operation}）。拆开后每条都有可静态判定的范围条件，
     * 既满足防全表更新的护栏，也保持原子性（service 方法在同一事务里）。
     */
    @Update("UPDATE newmusic.t_degrade_switch SET enabled = 0, "
            + "operator_id = #{operatorId}, version = version + 1 "
            + "WHERE degrade_level <= #{level} AND enabled <> 0")
    int disableUpTo(@Param("level") int level, @Param("operatorId") Long operatorId);

    @Update("UPDATE newmusic.t_degrade_switch SET enabled = 1, "
            + "operator_id = #{operatorId}, version = version + 1 "
            + "WHERE degrade_level > #{level} AND enabled <> 1")
    int enableAbove(@Param("level") int level, @Param("operatorId") Long operatorId);
}
