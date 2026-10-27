package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("后台用户查询")
public class AdminUserQueryReq extends PageQuery {

    private Integer status;
    private Integer userType;
    private Boolean banned;
}
