package com.example.newmusic.module.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ops.entity.MetricSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {

    /** 只删本模块自己的快照表，且必须有时间条件，禁止无界删除。 */
    @Delete("DELETE FROM newmusic.t_ops_metric_snapshot WHERE snapshot_time < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);

    // MAXVALUE 是 MySQL 保留字，maxValue 不加反引号会直接 1064
    @Select("SELECT metric_name AS metricName, AVG(metric_value) AS avgValue, "
            + "MAX(metric_value) AS `maxValue`, COUNT(*) AS samples "
            + "FROM newmusic.t_ops_metric_snapshot "
            + "WHERE snapshot_time >= DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) "
            + "GROUP BY metric_name")
    List<Map<String, Object>> aggregateRecent(@Param("minutes") int minutes);
}
