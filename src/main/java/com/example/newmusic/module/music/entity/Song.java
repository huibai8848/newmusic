package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_song")
@ApiModel("歌曲")
public class Song extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌曲名")
    private String name;

    /**
     * 拼音检索列（模块 06 D-35）。**写时物化**，由 {@code PinyinUtil} 在新增/改名时生成。
     *
     * <p>这几个字段被排除在 {@code SongSaveReq} 之外——它们是派生数据，
     * 允许客户端直接传「这首歌的拼音是什么」等于给了接口一个与 {@code name} 不一致的入口。
     */
    @ApiModelProperty("歌曲名全拼（小写、无分隔），写时物化，勿手工赋值")
    private String namePinyin;

    @ApiModelProperty("歌曲名拼音首字母（小写），写时物化")
    private String namePinyinFirst;

    @ApiModelProperty("歌手名全拼（冗余），写时物化")
    private String artistNamePinyin;

    @ApiModelProperty("歌手名拼音首字母（冗余），写时物化")
    private String artistNamePinyinFirst;

    private String subtitle;

    @ApiModelProperty("主歌手ID")
    private Long artistId;

    @ApiModelProperty("歌手名（冗余，搜索用）")
    private String artistName;

    private String featArtists;
    private String featNames;

    @ApiModelProperty("专辑ID")
    private Long albumId;

    @ApiModelProperty("专辑名（冗余）")
    private String albumName;

    private String cover;

    @ApiModelProperty("语种 国语/粤语/英语/日语/韩语")
    private String language;

    @ApiModelProperty("流派 流行/摇滚/说唱/民谣/电子/古风")
    private String genre;

    @ApiModelProperty("时长（秒）")
    private Integer duration;

    @ApiModelProperty("BPM")
    private Integer bpm;

    private LocalDate releaseDate;

    @ApiModelProperty("年代 80s/90s/00s/10s/20s")
    private String era;

    private String isrc;

    @ApiModelProperty("标准音质URL（128kbps mp3）")
    private String audioStandard;

    @ApiModelProperty("高清音质URL（320kbps mp3）")
    private String audioHigh;

    @ApiModelProperty("无损音质URL（flac）")
    private String audioLossless;

    private Long fileSizeStandard;
    private Long fileSizeHigh;
    private Long fileSizeLossless;

    private Long lyricId;

    @ApiModelProperty("是否有翻译歌词")
    private Integer hasTranslation;

    @ApiModelProperty("版权 0未授权 1已授权 2已过期 3受限")
    private Integer copyrightStatus;

    private String copyrightOwner;
    private LocalDate copyrightExpire;

    @ApiModelProperty("播放量（统计字段，普通接口不可改）")
    private Long playCount;

    private Long likeCount;
    private Long collectCount;
    private Long commentCount;
    private Long shareCount;
    private Long downloadCount;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    private BigDecimal completionRate;

    @ApiModelProperty("审核 0待审 1通过 2拒绝")
    private Integer auditStatus;

    @ApiModelProperty("状态 0下架 1上架")
    private Integer status;

    @ApiModelProperty("上架时间")
    private LocalDateTime publishTime;

    private Integer sortWeight;

    @ApiModelProperty("来源 1管理员录入 2第三方 3用户投稿")
    private Integer source;
}
