package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 批量埋点（SDK 默认 5 秒 / 20 条一批）。 */
@Data
@ApiModel("批量埋点")
public class TrackBatchReq {

    @ApiModelProperty(value = "事件列表，单次最多 200 条", required = true)
    private List<TrackEventReq> events;

    @ApiModelProperty("设备 ID（事件里缺失时回填）")
    private String deviceId;

    @ApiModelProperty("客户端版本")
    private String appVersion;
}
