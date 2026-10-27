package com.example.newmusic.module.play.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户播放设备 t_user_device（模块 12 FR-21-007 / BR-21-071）。
 *
 * <p>设备标识由前端生成并存在 localStorage（与 WebSocket 共用同一个 deviceId），
 * 因此同一浏览器多标签页只算一台设备。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_device")
@ApiModel("用户设备")
public class UserDevice extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    @ApiModelProperty("前端生成的设备 UUID")
    private String deviceId;

    @ApiModelProperty("设备名，如 Chrome on Windows")
    private String deviceName;

    @ApiModelProperty("WEB/PWA/MOBILE_WEB")
    private String deviceType;

    private LocalDateTime lastActiveTime;

    private String lastIp;

    @ApiModelProperty("1活跃 2已登出")
    private Integer status;
}
