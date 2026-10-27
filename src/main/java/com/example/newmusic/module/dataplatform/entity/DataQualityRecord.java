package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 数据质量校验记录（FR-26-011）。 */
@Data
@TableName("t_data_quality_record")
public class DataQualityRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate checkDate;
    private String tableName;
    private String partitionVal;
    private String ruleType;
    private String ruleExpr;
    private Integer passed;
    private String actualValue;
    private String severity;
    private LocalDateTime checkTime;
}
