package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据血缘（BR-26-097）。 */
@Data
@TableName("t_data_lineage")
public class DataLineage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String tableName;
    private String columnName;
    private String upstreamTable;
    private String upstreamColumn;
    private String jobName;
    private LocalDateTime createTime;
}
