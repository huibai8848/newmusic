package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("移动收藏")
public class CollectMoveReq {

    @NotEmpty(message = "请选择要移动的收藏")
    private List<Long> collectIds;

    @ApiModelProperty("目标收藏夹ID；null 表示默认收藏夹")
    private Long folderId;
}
