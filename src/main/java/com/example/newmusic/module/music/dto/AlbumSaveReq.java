package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@ApiModel("专辑新增/编辑请求")
public class AlbumSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "专辑名", required = true)
    @NotBlank(message = "专辑名不能为空")
    @Size(max = 200, message = "专辑名不能超过 200 字")
    private String name;

    @ApiModelProperty(value = "主歌手ID", required = true)
    @NotNull(message = "歌手不能为空")
    private Long artistId;

    private String cover;

    @ApiModelProperty("发行日期 yyyy-MM-dd")
    private LocalDate publishDate;

    private String company;

    @ApiModelProperty("专辑简介")
    private String description;

    private String language;

    @ApiModelProperty("1上架 0下架")
    private Integer status;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
