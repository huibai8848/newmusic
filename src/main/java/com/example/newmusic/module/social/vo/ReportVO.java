package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("我的举报")
public class ReportVO {

    private Long id;

    private String targetType;

    private String targetId;

    private String targetName;

    private String reasonType;

    private String reason;

    @ApiModelProperty("0待处理 1成立 2不成立 3需补充 4已合并")
    private Integer status;

    private String handleResult;

    private String handleReason;

    private String actionTaken;

    private LocalDateTime createTime;

    private LocalDateTime handledAt;

    @ApiModelProperty("合并到的主举报ID")
    private Long mergedIntoId;
}
