package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
@ApiModel("调整信用分")
public class CreditAdjustReq {

    @NotNull(message = "请填写变更分值")
    private Integer changeScore;

    @NotBlank(message = "请填写原因")
    @Size(min = 2, max = 500)
    private String reason;
}
