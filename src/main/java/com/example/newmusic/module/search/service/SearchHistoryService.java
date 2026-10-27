package com.example.newmusic.module.search.service;

import java.util.List;

/**
 * 搜索历史（FR-15-004 / BR-15-022~025，§6.3）。
 *
 * <p>权威副本是 Redis 的 List，{@code t_search_history} 只是「落库用于分析」的第二份。
 * 两者用途不同，所以**读只走 Redis**——不做「Redis 丢了从库里恢复」：
 * 那会恢复出跨设备的、更早的、用户已经删过的条目，与 BR-15-024「清空后为空」直接冲突。
 */
public interface SearchHistoryService {

    /** 记一条。**异步、不阻塞搜索响应**（BR-15-006）。未登录 / 功能关闭 / 空关键词时是空操作 */
    void recordAsync(Long userId, String keyword, int resultCount);

    /** 当前用户的历史，最新在前，最多 {@code newmusic.search.history.max} 条 */
    List<String> list(Long userId);

    /** 删掉一条（BR-15-025） */
    void remove(Long userId, String keyword);

    /** 清空（BR-15-024） */
    void clear(Long userId);
}
