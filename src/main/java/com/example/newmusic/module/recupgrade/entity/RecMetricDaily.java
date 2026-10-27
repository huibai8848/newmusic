package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 推荐指标日报（PRD §10）。 */
@Data
@TableName("t_rec_metric_daily")
public class RecMetricDaily {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate statDate;
    private String scene;
    private String experimentKey;
    private String groupName;
    private Long exposureCnt;
    private Long clickCnt;
    private Long playCnt;
    private Long validPlayCnt;
    private Long completeCnt;
    private Long likeCnt;
    private Long dislikeCnt;
    private Long skipCnt;
    private Long uniqueUsers;
    private BigDecimal ctr;
    private BigDecimal completionRate;
    private BigDecimal avgValidPlay;
    private BigDecimal longtailRatio;
    private BigDecimal diversityScore;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
