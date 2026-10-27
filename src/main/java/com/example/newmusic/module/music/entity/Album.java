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
@TableName("t_album")
@ApiModel("专辑")
public class Album extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("专辑名")
    private String name;

    @ApiModelProperty("专辑名全拼（小写、无分隔），写时物化（模块 06 D-35）")
    private String namePinyin;

    @ApiModelProperty("专辑名拼音首字母（小写），写时物化")
    private String namePinyinFirst;

    @ApiModelProperty("主歌手ID")
    private Long artistId;

    @ApiModelProperty("歌手名（冗余）")
    private String artistName;

    private String cover;
    private LocalDate publishDate;
    private String company;

    @ApiModelProperty("专辑简介")
    private String description;

    private String language;

    @ApiModelProperty("歌曲数（冗余）")
    private Integer songCount;

    @ApiModelProperty("点赞数（模块 10）")
    private Long likeCount;
    @ApiModelProperty("收藏数（模块 10）")
    private Long collectCount;
    @ApiModelProperty("评论数（模块 10）")
    private Long commentCount;
    @ApiModelProperty("分享数（模块 10）")
    private Long shareCount;

    private Integer hotScore;

    @ApiModelProperty("1上架 0下架")
    private Integer status;
}
