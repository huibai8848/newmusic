package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 线上线下一日特征一致性校验记录（FR-24-005）。 */
@Data
@TableName("t_rec_feature_consistency")
public class RecFeatureConsistency {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate checkDate;
    private String featureName;
    private Integer sampleSize;
    private BigDecimal offlineMean;
    private BigDecimal onlineMean;
    private BigDecimal deviation;
    private Integer passed;
    private LocalDateTime createTime;
}
