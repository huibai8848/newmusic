package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("收听时长上报")
public class ListenReportReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "本次上报的收听秒数，服务端会按真实间隔截断", required = true)
    @NotNull(message = "seconds 不能为空")
    @Min(value = 1, message = "seconds 最小为 1")
    private Integer seconds;
}
