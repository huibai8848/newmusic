package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("创建房间请求")
public class RoomCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "房间名 1-30 字符", required = true)
    @NotBlank(message = "房间名不能为空")
    @Size(max = 30, message = "房间名不能超过 30 字")
    private String name;

    @ApiModelProperty("封面；留空用房主头像")
    @Size(max = 500, message = "封面地址过长")
    private String cover;

    @ApiModelProperty(value = "1公开 2私密 3密码", example = "1")
    @Min(1)
    @Max(3)
    private Integer roomType = 1;

    @ApiModelProperty("密码房密码 4-16 位（仅 roomType=3）")
    @Size(max = 16, message = "密码不能超过 16 位")
    private String password;

    @ApiModelProperty("公告 ≤500 字符")
    @Size(max = 500, message = "公告不能超过 500 字")
    private String notice;

    @ApiModelProperty("主题标签，逗号分隔")
    @Size(max = 200, message = "标签过长")
    private String themeTags;

    @ApiModelProperty(value = "麦位数 4/6/8/12", example = "6")
    private Integer micCount = 6;

    @ApiModelProperty(value = "人数上限 50/100/200/500", example = "100")
    private Integer maxMembers = 100;

    @ApiModelProperty(value = "1房主控播 2自由播放 3投票切歌", example = "1")
    private Integer playMode = 1;

    @ApiModelProperty("点歌/上麦是否需要审核 0/1")
    private Integer needApprove = 1;

    @ApiModelProperty("是否允许自由上麦 0/1")
    private Integer allowFreeMic = 0;
}
