package com.example.newmusic.module.search.engine;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一页检索结果。{@code total} 与 {@code nextCursor} 是两种翻页方式的各自所需。 */
@Data
public class SearchPage {

    private List<SearchHit> hits = new ArrayList<>();

    /**
     * 命中总数。<b>{@code null} = 本次没有算</b>，见下。
     *
     * <p>游标翻页时**不查** total（{@code COUNT} 是另一遍全表扫，与主查询同量级）。
     * 所以这个字段必须能表达「没算」，否则只能退化成 0——而 0 是一个具体的断言，
     * 前端会把它渲染成「共 0 条」，出现在一个明明有结果的第 2 页上。
     * 「没算」和「算了，是 0」是两件事，用 {@code Long} 而不是 {@code long} 就是为了把它们分开；
     * 对外则靠 {@code @JsonInclude(NON_NULL)} 让这个字段直接消失。
     */
    private Long total;

    /** 下一页游标；无更多数据时为 null（BR-15-010） */
    private String nextCursor;

    private boolean hasMore;

    public static SearchPage empty() {
        return new SearchPage();
    }
}
