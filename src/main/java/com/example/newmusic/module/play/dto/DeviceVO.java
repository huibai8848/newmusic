package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备列表项（模块 12 BR-21-071）。
 */
@Data
@ApiModel("用户设备")
public class DeviceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;

    private String deviceName;

    @ApiModelProperty("WEB/PWA/MOBILE_WEB")
    private String deviceType;

    private LocalDateTime lastActiveTime;

    private String lastIp;

    @ApiModelProperty("是否当前发起请求的设备（不允许误退出自己）")
    private boolean current;
}
