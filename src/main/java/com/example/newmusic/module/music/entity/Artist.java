package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_artist")
@ApiModel("歌手")
public class Artist extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌手名")
    private String name;

    @ApiModelProperty("歌手名全拼（小写、无分隔），写时物化（模块 06 D-35）")
    private String namePinyin;

    @ApiModelProperty("歌手名拼音首字母（小写），写时物化")
    private String namePinyinFirst;

    @ApiModelProperty("别名/英文名")
    private String alias;

    private String avatar;
    private String cover;

    @ApiModelProperty("性别 0未知 1男 2女 3组合")
    private Integer gender;

    private String country;
    private LocalDate birthday;
    private LocalDate debutDate;

    @ApiModelProperty("简介")
    private String description;

    @ApiModelProperty("风格标签，逗号分隔")
    private String styles;

    @ApiModelProperty("歌曲数（冗余，由曲库维护）")
    private Integer songCount;

    @ApiModelProperty("专辑数（冗余）")
    private Integer albumCount;

    private Long followerCount;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    @ApiModelProperty("首字母（拼音检索）")
    private String firstLetter;

    @ApiModelProperty("1正常 0禁用")
    private Integer status;
}
