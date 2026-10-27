package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_content_audit_record")
public class ContentAuditRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int AUDIT_MACHINE = 1;
    public static final int AUDIT_MANUAL = 2;
    public static final int AUDIT_APPEAL = 3;

    public static final int DECISION_PASS = 1;
    public static final int DECISION_REJECT = 2;
    public static final int DECISION_REVIEW = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String contentType;
    private Long contentId;
    private Long userId;
    private String scene;
    private String contentSnapshot;
    private Integer auditType;
    private Integer decision;
    private String reason;
    private String hitWords;
    private Integer riskLevel;
    private String auditSource;
    private Integer wordVersion;
    private String modelVersion;
    private Long operatorId;
    private Integer costMs;
    private Integer incomplete;
    private LocalDateTime createTime;
}
