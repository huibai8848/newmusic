package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@ApiModel("歌手新增/编辑请求")
public class ArtistSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌手名", required = true)
    @NotBlank(message = "歌手名不能为空")
    @Size(max = 100, message = "歌手名不能超过 100 字")
    private String name;

    @ApiModelProperty("别名/英文名")
    private String alias;

    private String avatar;
    private String cover;

    @ApiModelProperty("性别 0未知 1男 2女 3组合")
    @Min(0)
    @Max(3)
    private Integer gender;

    private String country;
    private LocalDate birthday;
    private LocalDate debutDate;

    @ApiModelProperty("简介")
    private String description;

    @ApiModelProperty("风格标签，逗号分隔")
    private String styles;

    @ApiModelProperty("首字母（不传则按名称首字自动截取）")
    private String firstLetter;

    @ApiModelProperty("1正常 0禁用")
    private Integer status;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传，取自详情接口返回的 version")
    private Integer version;
}
