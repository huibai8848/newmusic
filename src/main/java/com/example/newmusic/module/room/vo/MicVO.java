package com.example.newmusic.module.room.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("麦位")
public class MicVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer micPos;
    private Long userId;
    private String nickname;
    private String avatar;

    @ApiModelProperty("0空闲 1占用 2锁定")
    private Integer status;

    @ApiModelProperty("占用者是否在线")
    private Boolean online;

    private Boolean locked;

    @ApiModelProperty("是否为房主专属位")
    private Boolean ownerSeat;
}
