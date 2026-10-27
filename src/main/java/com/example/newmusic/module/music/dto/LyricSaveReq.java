package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@ApiModel("歌词新增/编辑请求")
public class LyricSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌曲ID", required = true)
    @NotNull(message = "歌曲ID不能为空")
    private Long songId;

    @ApiModelProperty("LRC 原文（含时间轴，如 [00:12.34]歌词）")
    private String content;

    @ApiModelProperty("1标准LRC 2逐字LRC 3纯文本")
    private Integer contentType;

    @ApiModelProperty("翻译歌词")
    private String translation;

    private String transLang;

    @ApiModelProperty("罗马音")
    private String romaji;

    @ApiModelProperty("整体偏移（毫秒），范围 -10000~10000")
    private Integer offsetMs;

    private String summary;
    private String emotion;

    @ApiModelProperty("0待审 1通过 2拒绝")
    private Integer auditStatus;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
