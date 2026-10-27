package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("禁言请求")
public class MuteReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @ApiModelProperty(value = "5/30/60/1440 分钟；-1 表示永久（仅房主）", example = "30")
    @NotNull(message = "禁言时长不能为空")
    private Integer durationMinutes;

    @Size(max = 200, message = "原因过长")
    private String reason;
}
