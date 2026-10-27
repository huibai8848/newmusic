package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist")
@ApiModel("歌单")
public class Playlist extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_USER = 1;
    public static final int TYPE_EDITOR = 2;
    public static final int TYPE_ALGO = 3;
    public static final int TYPE_SCENE = 4;

    /** BR-04-010：单歌单歌曲数上限 */
    public static final int MAX_SONG_COUNT = 1000;

    @ApiModelProperty("歌单名")
    private String name;

    private String cover;
    private String description;

    @ApiModelProperty("创建者ID（0=系统歌单）")
    private Long userId;

    @ApiModelProperty("1用户歌单 2编辑歌单 3算法歌单 4场景歌单")
    private Integer playlistType;

    @ApiModelProperty("0私密 1公开")
    private Integer isPublic;

    @ApiModelProperty("标签，逗号分隔")
    private String tags;

    @ApiModelProperty("歌曲数（冗余）")
    private Integer songCount;

    private Long playCount;
    private Long collectCount;
    @ApiModelProperty("点赞数（模块 10）")
    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private Integer hotScore;

    @ApiModelProperty("是否首页推荐")
    private Integer isRecommend;

    private Integer sortWeight;

    @ApiModelProperty("0待审 1通过 2拒绝")
    private Integer auditStatus;

    @ApiModelProperty("1正常 0隐藏")
    private Integer status;

    /** 模块 18 FR-27-010：AI 生成标识，一旦置 1 不允许用户移除。 */
    @ApiModelProperty("是否 AI 生成 0否 1是")
    private Integer aiGenerated;

    @ApiModelProperty("生成模型（仅后台可见）")
    private String aiModel;

    @ApiModelProperty("Prompt 版本")
    private String aiPromptVersion;

    @ApiModelProperty("AI 生成时间")
    private java.time.LocalDateTime aiGeneratedAt;
}
