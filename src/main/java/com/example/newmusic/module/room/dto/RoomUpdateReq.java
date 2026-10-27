package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("修改房间请求")
public class RoomUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "乐观锁版本号", required = true)
    @NotNull(message = "version 不能为空")
    private Integer version;

    @Size(max = 30, message = "房间名不能超过 30 字")
    private String name;

    @Size(max = 500, message = "封面地址过长")
    private String cover;

    @Min(1)
    @Max(3)
    private Integer roomType;

    @Size(max = 16, message = "密码不能超过 16 位")
    private String password;

    @Size(max = 500, message = "公告不能超过 500 字")
    private String notice;

    @Size(max = 200, message = "标签过长")
    private String themeTags;

    private Integer micCount;
    private Integer maxMembers;
    private Integer playMode;
    private Integer needApprove;
    private Integer allowFreeMic;
}
