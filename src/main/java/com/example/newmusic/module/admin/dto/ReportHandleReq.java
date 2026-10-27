package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
@ApiModel("处理举报")
public class ReportHandleReq {

    @ApiModelProperty("1成立 2不成立 3待补充")
    @NotBlank(message = "请选择处理结论")
    @Pattern(regexp = "VALID|INVALID|NEED_MORE", message = "结论只能为 VALID / INVALID / NEED_MORE")
    private String result;

    @NotBlank(message = "请填写处理说明")
    @Size(min = 2, max = 500, message = "处理说明需为 2-500 字")
    private String reason;

    @ApiModelProperty("联动动作：NONE / OFFLINE_SONG / BAN_USER / CLOSE_ROOM")
    private String action;

    @ApiModelProperty("是否把同一目标的其它待处理举报合并到本条")
    private Boolean mergeSiblings;
}
