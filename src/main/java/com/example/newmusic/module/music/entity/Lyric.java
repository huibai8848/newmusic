package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_lyric")
@ApiModel("歌词")
public class Lyric extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌曲ID")
    private Long songId;

    @ApiModelProperty("LRC 原文（含时间轴）")
    private String content;

    @ApiModelProperty("1标准LRC 2逐字LRC 3纯文本")
    private Integer contentType;

    @ApiModelProperty("翻译歌词（同格式，保留时间轴）")
    private String translation;

    private String transLang;

    @ApiModelProperty("罗马音/音译")
    private String romaji;

    @ApiModelProperty("歌词整体偏移（毫秒，校准用）")
    private Integer offsetMs;

    private String summary;
    private String emotion;
    private Long contributorId;

    @ApiModelProperty("0待审 1通过 2拒绝")
    private Integer auditStatus;
}
