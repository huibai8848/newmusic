package com.example.newmusic.module.search.service.impl;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.entity.SearchHistory;
import com.example.newmusic.module.search.mapper.SearchHistoryMapper;
import com.example.newmusic.module.search.service.SearchHistoryService;
import com.example.newmusic.module.search.support.KeywordSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class SearchHistoryServiceImpl implements SearchHistoryService {

    private final RedisOps redisOps;
    private final SearchHistoryMapper historyMapper;
    private final SearchProperties properties;
    private final Executor executor;

    public SearchHistoryServiceImpl(RedisOps redisOps, SearchHistoryMapper historyMapper,
                                    SearchProperties properties,
                                    @Qualifier("searchExecutor") Executor executor) {
        this.redisOps = redisOps;
        this.historyMapper = historyMapper;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public void recordAsync(Long userId, String keyword, int resultCount) {
        if (userId == null || !properties.getHistory().isEnabled()) {
            // 仅登录用户记录（BR-15-006）：游客没有稳定的身份，为它记历史等于记「这个 IP 搜过什么」
            return;
        }
        String normalized = KeywordSupport.normalize(keyword);
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        try {
            executor.execute(() -> record(userId, normalized, resultCount));
        } catch (RejectedExecutionException e) {
            // 池已关闭（应用正在停止）：此时记录历史没有意义
            log.debug("搜索历史任务被拒绝（应用关闭中）userId={}", userId);
        }
    }

    private void record(Long userId, String keyword, int resultCount) {
        String key = RedisKeys.searchHistory(userId);
        Duration ttl = Duration.ofDays(properties.getHistory().getTtlDays());
        // 去重置顶（BR-15-023）：先删光同名条目，再推到最前，再裁到上限。
        // 三步的顺序不能换——先 LPUSH 再 LREM 会把刚推的那条也删掉。
        redisOps.listRemove(key, 0L, keyword);
        redisOps.listPush(key, keyword, ttl);
        redisOps.listTrim(key, 0L, properties.getHistory().getMax() - 1L);
        persist(userId, keyword, resultCount);
    }

    /**
     * 落库用于分析。
     *
     * <p>失败只记日志：这一份是**分析用**的副本，写失败不该让用户的搜索历史功能受影响
     * （Redis 那份已经成功了）。反过来，如果这里抛异常让它冒泡到异步线程，
     * 会打出一串无人处理的堆栈，而真正的用户可见行为其实完全正常。
     */
    private void persist(Long userId, String keyword, int resultCount) {
        try {
            SearchHistory row = new SearchHistory();
            row.setUserId(userId);
            row.setKeyword(keyword);
            row.setSearchType(1);
            row.setResultCount(resultCount);
            row.setIsClicked(0);
            historyMapper.insert(row);
        } catch (Exception e) {
            log.warn("搜索历史落库失败（不影响功能）userId={} keyword={} cause={}", userId, keyword, e.toString());
        }
    }

    @Override
    public List<String> list(Long userId) {
        if (userId == null) {
            return java.util.Collections.emptyList();
        }
        return redisOps.listRange(RedisKeys.searchHistory(userId), 0L, properties.getHistory().getMax() - 1L);
    }

    @Override
    public void remove(Long userId, String keyword) {
        if (userId == null || !StringUtils.hasText(keyword)) {
            return;
        }
        // count = 1：只删一条。去重置顶保证了同一个词最多出现一次，
        // 用 1 而不是 0 是为了「万一有重复（历史遗留数据），删一条就够了」
        redisOps.listRemove(RedisKeys.searchHistory(userId), 1L, keyword);
    }

    @Override
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        redisOps.delete(RedisKeys.searchHistory(userId));
    }
}
