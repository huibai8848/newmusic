package com.example.newmusic.module.recommend.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("推荐反馈")
public class FeedbackReq {

    private Long songId;

    @ApiModelProperty("歌手级反馈可不传 songId，由服务端补全")
    private Long artistId;

    @NotBlank(message = "action 不能为空")
    @ApiModelProperty(value = "LIKE | DISLIKE | DISLIKE_ARTIST | UNDO", required = true)
    private String action;

    @ApiModelProperty("DAILY | GUESS | SIMILAR | HOME")
    private String scene;

    @ApiModelProperty("在列表中的位置（位置偏差分析）")
    private Integer position;
}
