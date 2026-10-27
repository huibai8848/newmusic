package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("点歌请求")
public class QueueAddReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌曲ID", required = true)
    @NotNull(message = "songId 不能为空")
    private Long songId;
}
