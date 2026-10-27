package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_audit_rule")
public class AuditRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String ruleName;
    private String ruleType;
    private String pattern;
    private String scene;
    private String action;
    private Integer priority;
    private Integer status;
    private String remark;
    private Long creatorId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
