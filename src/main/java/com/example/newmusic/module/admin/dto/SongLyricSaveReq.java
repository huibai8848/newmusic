package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 后台保存歌曲歌词正文（批量导入「只补歌词」时用）。
 *
 * <p>只收正文与类型：翻译、罗马音、偏移在歌词模块各自的接口维护，
 * 一次导入不应该把它们清空。
 */
@Data
@ApiModel("后台歌曲歌词保存请求")
public class SongLyricSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌词正文（LRC 或纯文本）", required = true)
    @NotBlank(message = "歌词内容不能为空")
    @Size(max = 200000, message = "歌词内容过长")
    private String content;

    /**
     * 1 标准 LRC / 2 逐字 LRC / 3 纯文本。
     *
     * <p>用 {@code @Min/@Max} 而不是只靠 {@code upsertContent} 兜底：后台是直接暴露给运营的入口，
     * 传了 0 或 9 时应当在参数校验阶段就返回 10001，而不是把非法值写进 `t_lyric.content_type`。
     */
    @ApiModelProperty("歌词内容类型 1标准LRC 2逐字LRC 3纯文本；缺省按是否含时间轴自动判断")
    @Min(value = 1, message = "歌词内容类型仅支持 1/2/3")
    @Max(value = 3, message = "歌词内容类型仅支持 1/2/3")
    private Integer contentType;
}
