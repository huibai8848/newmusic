package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Data
@ApiModel("封禁用户")
public class BanReq {

    @NotBlank(message = "请填写封禁原因")
    @Size(min = 5, max = 500, message = "封禁原因需为 5-500 个字符")
    private String reason;

    @ApiModelProperty("解封时间；为空表示永久封禁")
    private LocalDateTime banUntil;

    @ApiModelProperty("批量封禁时的用户 ID，单次最多 100 个")
    private List<Long> userIds;
}
