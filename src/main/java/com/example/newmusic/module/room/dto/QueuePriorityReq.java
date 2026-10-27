package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("房主置顶请求")
public class QueuePriorityReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "pinned 不能为空")
    private Boolean pinned;
}
