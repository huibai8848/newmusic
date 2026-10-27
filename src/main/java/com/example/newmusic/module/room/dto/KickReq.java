package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("踢人请求")
public class KickReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @ApiModelProperty(value = "踢出原因（必填，BR-14-045）", required = true)
    @NotBlank(message = "踢人原因不能为空")
    @Size(max = 200, message = "原因过长")
    private String reason;
}
