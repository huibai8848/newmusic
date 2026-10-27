package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 告警静默（维护期），到期自动失效。 */
@Data
@TableName("t_ops_alert_silence")
public class AlertSilence implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 为空表示按级别静默 */
    private String ruleName;
    private String severity;
    private LocalDateTime untilTime;
    private String reason;
    private Long operatorId;
    private LocalDateTime createTime;
}
