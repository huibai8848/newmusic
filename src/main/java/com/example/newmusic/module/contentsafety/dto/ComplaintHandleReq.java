package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("处理侵权投诉")
public class ComplaintHandleReq {

    @ApiModelProperty("TAKE_DOWN / REJECT / NEED_MORE / COUNTER_NOTICE")
    @NotBlank(message = "请选择处理动作")
    private String action;

    @NotBlank(message = "请填写处理说明")
    @Size(min = 2, max = 500)
    private String result;

    @ApiModelProperty("是否通知上传者，默认 true")
    private Boolean notify;
}
