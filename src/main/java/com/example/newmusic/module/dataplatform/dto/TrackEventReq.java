package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Map;

/**
 * 单条埋点。兼容两种写法：
 * <ul>
 *   <li>PRD 26 的嵌套结构：{@code event + common + properties}；</li>
 *   <li>扁平结构：把公共属性直接放在顶层。</li>
 * </ul>
 * 公共属性由 SDK 注入，服务端仍会补 IP / 地区 / server_time / trace_id（BR-26-003）。
 */
@Data
@ApiModel("埋点事件")
public class TrackEventReq {

    @ApiModelProperty(value = "客户端生成的唯一 ID，5 分钟内幂等", required = true)
    private String eventId;

    @ApiModelProperty(value = "事件名 {domain}_{object}_{action}", required = true, example = "player_song_play")
    private String event;

    @ApiModelProperty("事件名（event 的别名，便于扁平调用）")
    private String eventName;

    @ApiModelProperty("客户端毫秒时间戳；缺失时用服务端时间")
    private Long eventTime;

    @ApiModelProperty("公共属性（嵌套写法）")
    private Map<String, Object> common;

    @ApiModelProperty("业务属性")
    private Map<String, Object> properties;

    @ApiModelProperty("业务属性别名（与 properties 等价）")
    private Map<String, Object> props;

    // ---- 扁平写法下的公共属性 ----
    private String deviceId;
    private String sessionId;
    private String platform;
    private String appVersion;
    private String os;
    private String browser;
    private String screen;
    private String network;
    private String pageId;
    private String pageUrl;
    private String referrer;
    private String traceId;
    private Map<String, String> abTests;
    private Boolean isNewUser;
}
