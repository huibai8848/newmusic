package com.example.newmusic.module.admin.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("登录日志查询")
public class LoginLogQueryReq extends PageQuery {

    private Integer loginType;
    private Integer result;
    private Boolean suspicious;
    private String ip;
    private LocalDate startDate;
    private LocalDate endDate;
}
