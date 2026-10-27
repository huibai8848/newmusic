package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 推荐请求日志。本环境用 MySQL 兜底 ClickHouse 的 t_rec_log。 */
@Data
@TableName("t_rec_log")
public class RecLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long userId;
    private String deviceId;
    private String scene;
    private String recallResult;
    private Integer candidateCount;
    private String modelVersion;
    private String featureVersion;
    private String finalList;
    private String costMs;
    private Integer degraded;
    private String degradeReason;
    private String experimentJson;
    private LocalDateTime createTime;
}
