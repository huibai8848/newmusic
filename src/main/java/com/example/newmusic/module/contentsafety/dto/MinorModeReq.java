package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Size;

@Data
@ApiModel("青少年模式开关")
public class MinorModeReq {

    @ApiModelProperty("true 开启 / false 关闭")
    private Boolean enabled;

    @ApiModelProperty("设置或校验模式密码")
    @Size(max = 64)
    private String password;

    /** 家长/监护人代设置时传目标用户；为空即当前登录用户。 */
    private Long userId;
}
