package com.example.newmusic.module.search.engine;

import lombok.Data;

import java.util.Collections;
import java.util.Set;

/**
 * 引擎中立的检索请求。
 *
 * <p>这是「换引擎不改上层」的支点：{@code SearchService} 只构造它，
 * {@link SearchEngine} 的实现各自翻译成自己的方言（MySQL 的加权 {@code LIKE} 表达式、
 * 将来 ES 的 {@code multi_match} + filter）。因此这里的字段必须是**业务语义**
 * （「要哪个字段加权」「按什么排」），不能出现 {@code LIKE}、{@code _score}、DSL 之类的引擎词汇。
 */
@Data
public class SearchRequest {

    /** 检索目标。{@code ALL} 不在这一层——综合是「四次独立检索」，由 Service 拆开 */
    public enum Target {
        SONG,
        ARTIST,
        ALBUM,
        PLAYLIST,
        /**
         * 歌词检索。**结果仍然是歌曲**，只是把命中范围收紧到「歌词里出现过这个词」——
         * 这是接口 {@code type=lyric} 的语义。单独一个枚举值而不是给 {@code SONG} 加个
         * boolean：在引擎侧它就是一条不同的检索规则（多一个 {@code WHERE}），
         * 而布尔参数会在新增其它变体时迅速退化成一堆开关。
         */
        LYRIC
    }

    /** 排序。取值与接口的 {@code sort} 参数一一对应（§4.3） */
    public enum Sort {
        /** 相关性优先（默认）。SQL：{@code score DESC, hot_score DESC, id DESC} */
        RELEVANCE,
        HOT,
        NEWEST,
        DURATION_ASC,
        DURATION_DESC
    }

    /** 原样关键词（已 trim）。引擎拿它匹配原文列 */
    private String keyword;

    /**
     * 拼音形式的关键词（小写、无分隔）。
     *
     * <p>{@code null} 表示**拼音列不参与本次检索**（BR-15-014：关键词含非 ASCII 字符）。
     * 用 null 而不是空串来表达「不参与」是有意的：SQL 里 {@code LIKE CONCAT('%', NULL, '%')}
     * 恒为 NULL、恒不命中，于是「含中文时不查拼音列」这条规则在 SQL 里不需要写任何分支，
     * 也不会因为将来有人往空串方向改写而悄悄失效。
     */
    private String pinyinKeyword;

    private Target target = Target.SONG;

    private Sort sort = Sort.RELEVANCE;

    /** 页码，从 1 开始。分页只影响 {@code LIMIT/OFFSET}，不参与打分 */
    private int pageNum = 1;

    private int pageSize = 20;

    /**
     * 不透明游标（BR-15-010 / D-40）。非空时忽略 {@code pageNum}，改用行值比较翻页。
     * 编码解码见 {@link SearchCursor}。
     */
    private String cursor;

    /** 过滤维度：维度内 OR、维度间 AND（BR-15-035），且不参与打分（BR-15-036 / §4.4） */
    private Set<String> languages = Collections.emptySet();
    private Set<String> genres = Collections.emptySet();
    private Set<String> eras = Collections.emptySet();
    /** {@code lt3} / {@code 3to5} / {@code gt5}，由引擎翻译成区间 */
    private Set<String> durations = Collections.emptySet();

    public boolean hasPinyin() {
        return pinyinKeyword != null && !pinyinKeyword.isEmpty();
    }

    /** 本页在结果集中的偏移量（{@code from}）。深分页的判定要用它（BR-15-010） */
    public int offset() {
        return (pageNum - 1) * pageSize;
    }

    public boolean hasCursor() {
        return cursor != null && !cursor.isEmpty();
    }
}
