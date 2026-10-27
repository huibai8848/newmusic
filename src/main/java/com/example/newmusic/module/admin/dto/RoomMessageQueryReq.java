package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("后台房间消息查询")
public class RoomMessageQueryReq extends PageQuery {

    @ApiModelProperty(value = "房间 ID", required = true)
    private Long roomId;

    @ApiModelProperty("1聊天 2弹幕 3系统 4礼物 5进房 6控播")
    private Integer messageType;

    @ApiModelProperty("0待审 1通过 2拦截")
    private Integer auditStatus;
}
