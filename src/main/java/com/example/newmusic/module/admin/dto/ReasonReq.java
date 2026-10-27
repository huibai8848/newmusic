package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("危险操作原因")
public class ReasonReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "操作原因，至少 5 个字", required = true)
    @NotBlank(message = "请填写操作原因")
    @Size(min = 5, max = 500, message = "操作原因需为 5-500 个字符")
    private String reason;
}
