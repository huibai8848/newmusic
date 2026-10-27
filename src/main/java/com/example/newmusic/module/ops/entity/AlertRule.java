package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 告警规则：指标 + 比较符 + 阈值 + for 时长 + 级别。 */
@Data
@TableName("t_ops_alert_rule")
public class AlertRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String ruleName;
    private String metric;
    private String operator;
    private Double thresholdValue;
    private Integer durationSeconds;
    private String severity;
    private Integer enabled;
    private String runbookKey;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public boolean matches(double value) {
        if ("LT".equalsIgnoreCase(operator)) {
            return value < thresholdValue;
        }
        if ("LE".equalsIgnoreCase(operator)) {
            return value <= thresholdValue;
        }
        if ("GE".equalsIgnoreCase(operator)) {
            return value >= thresholdValue;
        }
        return value > thresholdValue;
    }
}
