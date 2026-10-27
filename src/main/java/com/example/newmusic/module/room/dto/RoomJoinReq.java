package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("加入房间请求")
public class RoomJoinReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("密码房密码；非密码房可省略")
    @Size(max = 16, message = "密码不能超过 16 位")
    private String password;
}
