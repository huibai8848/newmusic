package com.example.newmusic.module.room.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("修改公告请求")
public class NoticeReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("公告内容；传空字符串表示清空")
    @Size(max = 500, message = "公告不能超过 500 字")
    private String notice;
}
