package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 告警记录（FR-28-007）。 */
@Data
@TableName("t_ops_alert_record")
public class AlertRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_FIRING = "firing";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_SILENCED = "silenced";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ruleId;
    private String alertName;
    private String severity;
    private String instance;
    private String summary;
    private Double metricValue;
    private Double thresholdValue;
    private String status;
    private LocalDateTime fireTime;
    private LocalDateTime resolveTime;
    private Long handlerId;
    private String handlerName;
    private String handleNote;
    private String dedupKey;
    private LocalDateTime createTime;
}
