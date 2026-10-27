package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 漏斗计算用的事件行（只取必要列）。 */
@Data
public class FunnelRow {
    private Long userId;
    private String deviceId;
    private String eventName;
    private LocalDateTime serverTime;
    private String props;
    private String pageId;
    private String platform;
    private String os;
    private String network;
    private String region;
    private Integer isNewUser;
}
