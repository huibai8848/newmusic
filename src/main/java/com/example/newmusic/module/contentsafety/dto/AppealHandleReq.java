package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("处理申诉")
public class AppealHandleReq {

    @ApiModelProperty("APPROVE / REJECT")
    @NotBlank(message = "请选择处理结论")
    private String decision;

    @NotBlank(message = "请填写处理说明")
    @Size(min = 2, max = 500)
    private String result;

    @ApiModelProperty("申诉成功时是否恢复信用分，默认 true")
    private Boolean restoreCredit;
}
