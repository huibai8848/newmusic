package com.example.newmusic.module.ws.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

/** 管理端通知推送请求（FR-13-005「系统公告」）。 */
@Data
@ApiModel("通知推送请求")
public class NotificationPushReq {

    @ApiModelProperty(value = "接收者用户ID列表（至少一个）", required = true)
    @NotEmpty
    private List<Long> userIds;

    @ApiModelProperty("1系统 2评论回复 3点赞 4关注 5私信 6@提醒 7房间邀请 8审核结果 9举报结果")
    private Integer notifyType = 1;

    @ApiModelProperty(value = "标题", required = true)
    @NotBlank
    @Size(max = 200)
    private String title;

    @ApiModelProperty("正文")
    @Size(max = 1000)
    private String content;

    @ApiModelProperty("跳转目标类型")
    private Integer targetType;

    @ApiModelProperty("跳转目标ID")
    private Long targetId;
}
