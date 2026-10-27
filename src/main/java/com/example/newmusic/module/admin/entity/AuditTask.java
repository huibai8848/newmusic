package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审核任务。表里没有 deleted / create_by，故不继承 BaseEntity；
 * 并发控制用显式 {@code version} 条件更新（见 {@code AuditTaskMapper}）。
 */
@Data
@TableName("t_audit_task")
@ApiModel("审核任务")
public class AuditTask implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CLAIMED = 1;
    public static final int STATUS_APPROVED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_CLOSED = 4;

    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_NORMAL = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskType;
    /** 模块 18：审核场景，如 COMMENT/POST/PLAYLIST/ROOM/DANMAKU/MESSAGE/IMAGE/APPEAL */
    private String scene;
    private String targetType;
    private String targetId;
    private Integer priority;
    private Integer status;
    private Long submitterId;
    private String title;
    private String contentSnapshot;
    private String machineResult;
    private BigDecimal machineScore;

    private Long claimedBy;
    private LocalDateTime claimedAt;
    private LocalDateTime claimExpireAt;

    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleResult;
    private String rejectReason;
    private Integer actionApplied;

    private Integer escalated;
    private LocalDateTime escalatedAt;
    /** 模块 18：SLA 截止时间 */
    private LocalDateTime slaDeadline;
    private Long relatedReportId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer version;
}
