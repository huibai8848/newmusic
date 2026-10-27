package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_audit_record")
public class AuditRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;
    private Long auditorId;
    private String auditorName;
    private String action;
    private Integer beforeStatus;
    private Integer afterStatus;
    private String reason;
    private LocalDateTime createTime;
}
