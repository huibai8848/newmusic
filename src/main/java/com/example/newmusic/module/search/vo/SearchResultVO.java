package com.example.newmusic.module.search.vo;

import com.example.newmusic.common.result.PageResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 搜索结果（§5.2）。综合 Tab 与分类 Tab **共用一个 VO**。
 *
 * <p>共用的理由：两种 Tab 的字段名完全一致（综合只是把 {@code list} 换成四个分类段），
 * 拆成两个类会让前端多一套类型定义、后端多一处映射，而收益只是「字段不会用错」——
 * 这点收益由 {@link JsonInclude} 兜住了：用不到的分段直接不出现在 JSON 里，
 * 前端拿到的对象形状本身就是自解释的。
 *
 * <p>{@code NON_NULL} 是这里的关键：没有它，综合搜索的响应里会躺着
 * {@code "list": null, "total": null, "songs": null} 这些噪音，前端每取一个字段
 * 都要判空。有了它，「这个字段存在」就等于「它在这个 Tab 下有值」。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel("搜索结果")
public class SearchResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("本次搜索的关键词；空关键词搜索时为 null")
    private String keyword;

    @ApiModelProperty("all / song / artist / album / playlist / lyric")
    private String type;

    /**
     * 是否降级。
     *
     * <p>PRD 的 {@code EX-15-001-1} 要求用**响应头**标记，本实现在响应体里也给一份（D-48）：
     * 响应头对前端不友好（跨域要额外 {@code Access-Control-Expose-Headers}，且没有类型），
     * 而响应体里前端直接读。两份都写，头那份留给网关与监控。
     */
    @ApiModelProperty("是否降级（当前使用的不是首选搜索引擎）")
    private Boolean degraded;

    @ApiModelProperty("本次检索耗时（毫秒），用于前端做慢查询埋点与 P95 统计")
    private Long costMs;

    /**
     * 实际使用的搜索引擎（{@code es} / {@code mysql}）。
     *
     * <p>与 {@code degraded} 的区别：降级只说明「不是首选」，引擎名说明「到底是谁在算」。
     * 排查「为什么同一个词换台机器排序不一样」时，先看这一项能省掉一轮猜测。
     */
    @ApiModelProperty("实际使用的搜索引擎：es / mysql")
    private String engine;

    /** 分类 Tab 才有 */
    @ApiModelProperty("分类 Tab：总条数")
    private Long total;

    @ApiModelProperty("分类 Tab：页码")
    private Long pageNum;

    @ApiModelProperty("分类 Tab：每页条数")
    private Long pageSize;

    @ApiModelProperty("分类 Tab：是否还有下一页")
    private Boolean hasMore;

    @ApiModelProperty("分类 Tab：下一页游标；无更多数据时为 null（BR-15-010）")
    private String cursor;

    /**
     * 分类 Tab 的当前页结果。
     *
     * <p><b>元素类型由 {@code type} 决定</b>：{@code song}/{@code lyric} → {@link SearchSongVO}，
     * {@code artist} → {@link SearchArtistVO}，{@code album} → {@link SearchAlbumVO}，
     * {@code playlist} → {@link SearchPlaylistVO}。
     *
     * <p>为什么是这个泛型而不是四个各自的字段：接口契约（§5.2）里分类 Tab 只有一个
     * {@code list}，前端的列表组件也只吃一个数组。真要强类型，代价是前端要按 type
     * 去四个字段里挑，而后端要维护四个几乎一样的序列化分支。用一个 {@code List<Object>}
     * 把「形状随 type 变」这件事**显式说出来**，比用四个字段假装它是强类型的更诚实。
     */
    @ApiModelProperty("分类 Tab：当前页结果（元素类型由 type 决定）")
    private List<Object> list;

    // ---------- 综合 Tab 的四个分段（各自有独立配额，BR-15-004） ----------

    @ApiModelProperty("综合 Tab：歌曲段（配额 10）")
    private PageResult<SearchSongVO> songs;

    @ApiModelProperty("综合 Tab：歌手段（配额 3）")
    private PageResult<SearchArtistVO> artists;

    @ApiModelProperty("综合 Tab：专辑段（配额 3）")
    private PageResult<SearchAlbumVO> albums;

    @ApiModelProperty("综合 Tab：歌单段（配额 3）")
    private PageResult<SearchPlaylistVO> playlists;

    @ApiModelProperty("无结果时的推荐词（BR-15-006-3 / D-41）")
    private List<String> suggestWords;

    // ---------- 空关键词（BR-15-001） ----------

    @ApiModelProperty("空关键词时返回的热搜榜")
    private List<HotWordVO> hotWords;

    @ApiModelProperty("空关键词时返回的当前用户搜索历史（未登录为 null）")
    private List<String> history;

    @ApiModelProperty("降级等原因需要告诉用户的一句话；正常时为 null")
    private String notice;
}
