package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 埋点明细（ODS 层；本机以 MySQL 代替 Kafka + HDFS）。 */
@Data
@TableName("t_track_event")
public class TrackEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String eventName;
    /** 客户端毫秒时间戳，分析时只用于行为顺序（BR-26-010） */
    private Long eventTime;
    /** 服务端接收时间，所有指标口径都用它 */
    private LocalDateTime serverTime;
    private Long userId;
    private String deviceId;
    private String sessionId;
    private String platform;
    private String appVersion;
    private String os;
    private String browser;
    private String screen;
    private String network;
    private String ip;
    private String region;
    private String pageId;
    private String pageUrl;
    private String referrer;
    private String traceId;
    private String abTests;
    private Integer isNewUser;
    /** 已脱敏的业务属性 JSON */
    private String props;
    private LocalDate dt;
    private LocalDateTime createTime;
}
