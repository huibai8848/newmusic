package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("榜单隐身设置")
public class RankHiddenReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "hidden 不能为空")
    private Boolean hidden;
}
