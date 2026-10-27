package com.example.newmusic.module.contentsafety.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("审核队列查询")
public class AuditQueueQuery extends PageQuery {

    @ApiModelProperty("任务类型：COMMENT/POST/PLAYLIST/ROOM/DANMAKU/MESSAGE/IMAGE/APPEAL")
    private String type;

    private String scene;
    private Integer status;
    private Integer priority;
    private Long claimedBy;
    private Boolean overdue;
}
