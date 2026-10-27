package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("提交申诉")
public class AppealCreateReq {

    @ApiModelProperty("CONTENT / ACCOUNT")
    @NotBlank(message = "请选择申诉对象")
    private String targetType;

    @ApiModelProperty("处置记录 ID（内容处置填内容审核记录 ID，账号处置填 t_user_restriction ID）")
    @NotBlank(message = "请选择处置记录")
    private String targetId;

    @NotBlank(message = "请填写申诉理由")
    @Size(max = 1000, message = "申诉理由最多 1000 字")
    private String reason;

    @Size(max = 6, message = "证据图片最多 6 张")
    private List<String> evidenceImages;
}
