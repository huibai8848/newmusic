package com.example.newmusic.module.search.engine;

/**
 * 搜索引擎端口（系分 D-33）。
 *
 * <p>PRD 假定 Elasticsearch 是唯一引擎，并在 BR-15-009 里额外要求「ES 不可用时降级为
 * MySQL LIKE 查询」。也就是说 **MySQL 这条路本来就是 PRD 要求永久保留的**，
 * 不是脚手架。既然如此，把它做成一个端口下的一个实现，比做成 ES 实现里的一段 if 更贴合需求：
 * 降级从「兜底代码」变成了「路由到另一个引擎」，而路由是可以被单测覆盖的
 * （{@code SearchEngineRouterTest}，T612）。
 *
 * <p>实现的约定：{@link #search} **抛异常即视为本引擎不可用**，由
 * {@link SearchEngineRouter} 捕获并转下一个引擎。实现不要去猜「什么算故障」——
 * 抛了就是故障，返回了（哪怕空列表）就是成功。
 */
public interface SearchEngine {

    /** 引擎名，与 {@code newmusic.search.engine} 配置值对应（{@code mysql} / {@code es}） */
    String name();

    /**
     * 当前是否可用。
     *
     * <p>必须是**廉价的本地判断**（熔断状态、连接是否建立），不能每次都去探一次远端——
     * 它会被每个搜索请求调用。真正的故障探测靠 {@link #search} 抛异常后把状态翻掉。
     */
    boolean isAvailable();

    SearchPage search(SearchRequest request);
}
