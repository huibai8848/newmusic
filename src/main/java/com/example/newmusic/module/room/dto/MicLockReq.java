package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("锁定/解锁麦位请求")
public class MicLockReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "麦位序号，1 号麦不可锁", required = true)
    @NotNull(message = "micPos 不能为空")
    @Min(2)
    @Max(12)
    private Integer micPos;

    @ApiModelProperty("true 锁定 / false 解锁")
    @NotNull(message = "locked 不能为空")
    private Boolean locked;
}
