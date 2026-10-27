package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("解散房间请求")
public class DissolveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "输入房间名以确认（BR-14-067）", required = true)
    @NotBlank(message = "请输入房间名确认")
    @Size(max = 30, message = "房间名过长")
    private String confirmName;
}
