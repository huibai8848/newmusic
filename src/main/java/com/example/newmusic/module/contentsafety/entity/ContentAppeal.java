package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 申诉（复用既有 {@code t_appeal}，模块 18 增补证据/结果/SLA/原审核员列）。
 */
@Data
@TableName("t_appeal")
public class ContentAppeal implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    public static final String TARGET_CONTENT = "CONTENT";
    public static final String TARGET_ACCOUNT = "ACCOUNT";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String targetType;
    private String targetId;
    private String reason;
    private String evidenceImages;
    private Integer status;
    private String result;
    private String reply;
    private Long handledBy;
    private LocalDateTime handledAt;
    private Long originalOperatorId;
    private LocalDateTime slaDeadline;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
