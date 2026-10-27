package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** A/B 实验（FR-24-008）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_experiment")
public class RecExperiment extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_STOPPED = 3;
    public static final int STATUS_ARCHIVED = 4;

    private String experimentKey;
    private String name;
    private String description;
    private String layer;
    private String mutexGroup;
    private Integer trafficPercent;
    private String layerSalt;
    private String groupsJson;
    private String primaryMetric;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long creatorId;
    private Long approverId;
    private String conclusion;
    /** DB 为权威的白名单缓存：{"userId":"group"}；Redis 只是读缓存 */
    private String whitelistJson;
}
