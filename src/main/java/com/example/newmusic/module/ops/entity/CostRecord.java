package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 成本日记录（FR-28-013）。金额单位统一为「分」。 */
@Data
@TableName("t_cost_record")
public class CostRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate costDate;
    private String category;
    private String service;
    private Long amountCent;
    private Long budgetCent;
    private String detail;
    private LocalDateTime createTime;
}
