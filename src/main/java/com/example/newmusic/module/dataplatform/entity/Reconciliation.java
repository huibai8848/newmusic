package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 数仓与业务库对账（BR-26-103）。 */
@Data
@TableName("t_reconciliation")
public class Reconciliation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate checkDate;
    private String item;
    private Long dwValue;
    private Long bizValue;
    private BigDecimal deviation;
    private BigDecimal threshold;
    private Integer passed;
    private LocalDateTime createTime;
}
