package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 混沌实验记录（FR-28-011）。爆炸半径与时长由服务层强制上限。 */
@Data
@TableName("t_chaos_experiment")
public class ChaosExperiment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_STOPPED = "STOPPED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String experimentName;
    private String target;
    private String faultType;
    private String hypothesis;
    private Integer blastRadius;
    private Integer durationSeconds;
    private String status;
    private String result;
    private String findings;
    private Long operatorId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
