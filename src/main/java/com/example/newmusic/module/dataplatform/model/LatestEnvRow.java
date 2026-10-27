package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 用户最近一次上报的设备环境。 */
@Data
public class LatestEnvRow {
    private Long userId;
    private String platform;
    private String os;
    private String network;
}
