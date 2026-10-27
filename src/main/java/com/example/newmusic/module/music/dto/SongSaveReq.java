package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
@ApiModel("歌曲新增/编辑请求")
public class SongSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌曲名", required = true, example = "晴天")
    @NotBlank(message = "歌曲名不能为空")
    @Size(max = 200, message = "歌曲名不能超过 200 字")
    private String name;

    @ApiModelProperty("副标题")
    @Size(max = 200, message = "副标题过长")
    private String subtitle;

    @ApiModelProperty(value = "主歌手ID", required = true)
    @NotNull(message = "歌手不能为空")
    private Long artistId;

    @ApiModelProperty("合作歌手ID，逗号分隔")
    private String featArtists;

    @ApiModelProperty("合作歌手名，逗号分隔")
    private String featNames;

    @ApiModelProperty("专辑ID")
    private Long albumId;

    @ApiModelProperty("封面URL")
    private String cover;

    @ApiModelProperty("语种")
    private String language;

    @ApiModelProperty("流派")
    private String genre;

    @ApiModelProperty(value = "时长（秒）", example = "269")
    @Min(value = 0, message = "时长不合法")
    @Max(value = 65535, message = "时长不合法")
    private Integer duration;

    @ApiModelProperty("BPM")
    private Integer bpm;

    @ApiModelProperty("发行日期 yyyy-MM-dd")
    private LocalDate releaseDate;

    @ApiModelProperty("年代 80s/90s/00s/10s/20s")
    private String era;

    @ApiModelProperty("ISRC")
    @Pattern(regexp = "^[a-zA-Z0-9\\u4e00-\\u9fa5]{0,50}$", message = "ISRC 格式不合法")
    private String isrc;

    @ApiModelProperty("标准音质URL（128kbps mp3）")
    private String audioStandard;

    @ApiModelProperty("高清音质URL（320kbps mp3）")
    private String audioHigh;

    @ApiModelProperty("无损音质URL（flac）")
    private String audioLossless;

    @ApiModelProperty("歌词ID")
    private Long lyricId;

    /**
     * 歌词正文（可选）。新增/编辑/批量导入时随歌曲一起写入 `t_lyric`。
     *
     * <p>导入本地音频时，脚本会把同目录的 `.lrc` 或文件内嵌歌词（ID3 USLT / FLAC LYRICS）
     * 塞进这个字段——否则「歌导入成功了但没有歌词」是必然结果：音频文件本身不携带
     * 平台的歌词记录，`t_lyric` 也没有别的自动来源。
     */
    @ApiModelProperty("歌词正文（LRC 或纯文本，可选；已存在则覆盖正文）")
    @Size(max = 200000, message = "歌词内容过长")
    private String lyric;

    @ApiModelProperty("歌词内容类型 1标准LRC 2逐字LRC 3纯文本；缺省按是否含时间轴自动判断")
    private Integer lyricContentType;

    @ApiModelProperty("版权 0未授权 1已授权 2已过期 3受限")
    private Integer copyrightStatus;

    @ApiModelProperty("版权方")
    private String copyrightOwner;

    @ApiModelProperty("版权到期日 yyyy-MM-dd")
    private LocalDate copyrightExpire;

    @ApiModelProperty("审核状态 0待审 1通过 2拒绝")
    private Integer auditStatus;

    @ApiModelProperty("状态 0下架 1上架；上架需满足 BR-04-001/002")
    private Integer status;

    @ApiModelProperty("排序权重")
    private Integer sortWeight;

    @ApiModelProperty("标签ID列表，最多 10 个；全量覆盖")
    @Size(max = 10, message = "单曲最多 10 个标签")
    private List<Long> tagIds;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
