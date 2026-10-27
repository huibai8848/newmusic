package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("审批上麦请求")
public class MicApproveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "申请人ID", required = true)
    @NotNull(message = "userId 不能为空")
    private Long userId;

    @ApiModelProperty("true 通过 / false 拒绝")
    @NotNull(message = "approve 不能为空")
    private Boolean approve;
}
