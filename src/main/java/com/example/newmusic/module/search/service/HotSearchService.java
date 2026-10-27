package com.example.newmusic.module.search.service;

import com.example.newmusic.module.search.vo.HotSearchVO;
import com.example.newmusic.module.search.vo.HotWordVO;

import java.util.List;

/**
 * 热搜榜（FR-15-005 / BR-15-028~034，§6.2）。
 *
 * <p>数据分三层，各有用处，不要混：
 * <ol>
 *   <li><b>实时计数</b> {@code ml:search:hot:realtime}（ZSet）：每次搜索 ZINCRBY 累加，
 *       是唯一的事实来源；</li>
 *   <li><b>展示快照</b> {@code ml:search:hot:snapshot}（TTL 300s）：5 分钟内所有人看到
 *       同一份榜单（BR-15-032）。没有它，榜单会在每次请求间跳来跳去，
 *       用户会觉得「榜单在乱抖」；</li>
 *   <li><b>落库</b> {@code t_search_hot}：每小时一次，供运营查看与空榜重建。</li>
 * </ol>
 */
public interface HotSearchService {

    /**
     * 记一次搜索。**异步、不阻塞搜索响应**（BR-15-007）。
     *
     * <p>内部做三件事：敏感词/无意义词直接丢弃、同主体同词的去重（EX-15-005-1）、
     * ZINCRBY 累加。
     *
     * @param subject 去重主体，形如 {@code u:123} / {@code ip:1.2.3.4}
     */
    void recordAsync(String keyword, String subject);

    /** 当前榜单（快照）。Redis 与库都不可用时返回配置里的兜底词，不抛异常 */
    HotSearchVO snapshot();

    /** 只要词，给联想的兜底用（BR-15-021）与降级时的「热门内容」用 */
    List<String> plainWords();

    /**
     * 整点落库（BR-15-031）。{@code @Scheduled} 驱动，返回写入条数便于日志与测试断言。
     *
     * <p>不是 {@code void}：定时任务最怕「跑了但什么都没做」与「根本没跑」看起来一样。
     */
    int persistDaily();

    /** 直接读快照里的词条（不触发重建），给降级路径用；可能为空 */
    List<HotWordVO> cachedWords();
}
