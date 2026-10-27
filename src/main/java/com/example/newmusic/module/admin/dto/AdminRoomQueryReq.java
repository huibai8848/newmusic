package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("后台房间查询")
public class AdminRoomQueryReq extends PageQuery {

    private Integer status;
    private Integer roomType;
    private Long ownerId;
}
