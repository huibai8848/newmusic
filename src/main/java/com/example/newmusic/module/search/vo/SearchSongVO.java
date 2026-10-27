package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 搜索命中的歌曲（§5.2）。
 *
 * <p>与曲库模块的歌曲 VO 刻意不同：多了 {@code score}/{@code matchedFields}/
 * {@code highlight}。搜索结果的展示与列表页不同——用户需要知道「为什么它排第一」，
 * 前端要靠 {@code matchedFields} 决定把高亮放在歌名还是歌手名上。
 */
@Data
@ApiModel("搜索结果-歌曲")
public class SearchSongVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌曲ID")
    private Long id;

    @ApiModelProperty("歌曲名")
    private String name;

    @ApiModelProperty("歌手ID")
    private Long artistId;

    @ApiModelProperty("歌手名")
    private String artistName;

    @ApiModelProperty("专辑ID")
    private Long albumId;

    @ApiModelProperty("专辑名")
    private String albumName;

    @ApiModelProperty("封面")
    private String cover;

    @ApiModelProperty("时长（秒）")
    private Integer duration;

    @ApiModelProperty("语种")
    private String language;

    @ApiModelProperty("流派")
    private String genre;

    @ApiModelProperty("年代")
    private String era;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    @ApiModelProperty("相关性得分（§4.2 的加权命中数）")
    private Integer score;

    @ApiModelProperty("命中字段，如 [\"name\",\"lyric\"]")
    private List<String> matchedFields;

    /**
     * 高亮片段：字段名 → 已转义并包裹 {@code <em class="hl">} 的 HTML。
     *
     * <p><b>前端必须用 {@code dangerouslySetInnerHTML} 渲染它。</b>
     * 值里的用户内容已经由服务端转义（D-37），前端**不要再转一次**——
     * 再转一次会把 {@code <em>} 变成字面文本，高亮就没了。
     */
    @ApiModelProperty("高亮片段，键为字段名（name/artistName/albumName/lyric）")
    private Map<String, String> highlight;

    @ApiModelProperty("版权状态 0未授权 1已授权 2已过期 3受限")
    private Integer copyrightStatus;

    @ApiModelProperty("是否可播放。false 时前端置灰（D-42 的开关打开时才会出现 false）")
    private Boolean playable;
}
