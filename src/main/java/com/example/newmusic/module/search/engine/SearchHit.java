package com.example.newmusic.module.search.engine;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 引擎中立的命中项。
 *
 * <p>刻意做成**扁平的一张大表**而不是「歌曲/歌手/专辑/歌单四个子类」：
 * 四类命中在展示上确实不同，但引擎侧的差别很小（都是「一行 + 一个分数 + 命中过哪些字段」），
 * 多态会让「MySQL 的一次 UNION 查询怎么映射」变成一个需要类型标签的问题。
 * 用不上的字段留 null，由 VO 那层决定暴露哪些——VO 才是对外契约，
 * 这个类只是引擎与 Service 之间的内部信封，不进 JSON。
 */
@Data
public class SearchHit {

    /** 命中过哪些字段。既是给前端看的「为什么它排在这里」，也是判定 {@code type=lyric} 的依据 */
    public enum Field {
        NAME,
        NAME_PINYIN,
        NAME_PINYIN_FIRST,
        ARTIST_NAME,
        ARTIST_NAME_PINYIN,
        ALBUM_NAME,
        /** 歌手别名/英文名（搜「Jay」命中周杰伦就走这里，权重 2） */
        ALIAS,
        TAG,
        LYRIC
    }

    private Long id;

    private SearchRequest.Target target;

    /** 相关性得分。MySQL 实现是「加权命中数」（§4.2）；换成 ES 后语义变成 {@code _score} */
    private int score;

    /**
     * 精确分（ES 的 BM25 浮点值）。
     *
     * <p>展示与排序断言用 {@link #score}（取整，前端契约是整数）；但**游标翻页必须用它**：
     * {@code search_after} 拿整数去比 ES 的浮点 {@code _score}，同分的两页会互相越界，
     * 表现是「翻到第 2 页仍是第 1 页那几条、永远翻不到末页」。
     *
     * <p>MySQL 实现不填（保持 0），游标编码时回落到 {@link #score}。
     */
    private double exactScore;

    private Set<Field> matchedFields = EnumSet.noneOf(Field.class);

    // ---------- 展示字段（按 target 取用） ----------
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

    /** 专辑/歌单的曲目数 */
    private Integer songCount;
    /** 歌单播放数 */
    private Long playCount;

    /**
     * 歌词命中片段（**已剥离 LRC 时间轴、未转义、已按窗口截断**）。
     *
     * <p>由引擎在 SQL 里就地切出，而不是把整篇歌词带回来：
     * 歌词是 LONGTEXT，一首歌几百行，20 条命中就是几 MB 的响应体。
     * 高亮留给 {@code HighlightSupport} 做（D-37：转义必须在标签之前，所以这里给的是原文）。
     */
    private String lyricFragment;

    public void mark(Field field) {
        this.matchedFields.add(field);
    }
}
