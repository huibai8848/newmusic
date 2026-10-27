package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("收藏项")
public class CollectVO {

    private Long id;

    private String targetType;

    private Long targetId;

    private String name;

    private String cover;

    private String subtitle;

    private Long folderId;

    @ApiModelProperty("当前用户是否已收藏")
    private Boolean collected;

    @ApiModelProperty("目标已下架/删除时为 true，前端置灰")
    private Boolean offline;

    private LocalDateTime createTime;
}
