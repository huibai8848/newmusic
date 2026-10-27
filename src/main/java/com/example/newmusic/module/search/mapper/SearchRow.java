package com.example.newmusic.module.search.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索 SQL 的一行原始投影。
 *
 * <p><b>为什么不直接映射成 {@link com.example.newmusic.module.search.engine.SearchHit}。</b>
 * {@code SearchHit.matchedFields} 是 {@code EnumSet<Field>}，而 SQL 里命中与否是六七个
 * 0/1 的列。让 MyBatis 去把这些 0/1 拼成枚举集合，需要一个自定义 TypeHandler
 * 去解析 {@code "name,lyric"} 这样的字符串——等于把「哪几个字段命中了」这条业务规则
 * 塞进一个序列化细节里。分成两步（SQL → SearchRow → SearchHit）之后，
 * 「打分」和「命中溯源」都在 Java 里，可以单独断言。
 *
 * <p>{@code mt*} 前缀 = matched。它们是 0/1 而不是 boolean，因为 MySQL 没有 boolean，
 * 而 {@code IFNULL(..., 0)} 的结果就是整数。
 */
@Data
public class SearchRow {

    private Long id;
    private String name;

    private Long artistId;
    private String artistName;
    private Long albumId;
    private String albumName;
    private String cover;

    private String language;
    private String genre;
    private String era;
    private Integer duration;
    private Integer hotScore;
    private Integer copyrightStatus;
    private LocalDateTime publishTime;

    private Integer songCount;
    private Long playCount;

    /** 加权命中分（§4.2）。同一行在四个 target 下的打分表达式不同，列名一致 */
    private Integer score;

    /** 命中过「歌曲名」 */
    private Integer mtName;
    /** 命中过「歌曲名全拼」 */
    private Integer mtNamePinyin;
    /** 命中过「歌曲名首字母」 */
    private Integer mtNamePinyinFirst;
    /** 命中过「歌手名」 */
    private Integer mtArtistName;
    /** 命中过「歌手名拼音」 */
    private Integer mtArtistNamePinyin;
    /** 命中过「专辑名」 */
    private Integer mtAlbumName;
    /** 命中过「歌手别名/英文名」。只有歌手 target 有这个列 */
    private Integer mtAlias;
    /** 命中过「标签」 */
    private Integer mtTag;
    /** 命中过「歌词」。同时也是 {@code type=lyric} 的判定依据 */
    private Integer mtLyric;

    /** 歌词命中处的原文片段（已剥离 LRC 时间轴、未转义、已截断） */
    private String lyricFragment;
}
