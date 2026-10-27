package com.example.newmusic.module.recupgrade.recall;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallItem;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.model.RecallSource;
import com.example.newmusic.module.recupgrade.service.RecMetricsService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行召回引擎（FR-24-001）。
 *
 * <p>三条硬规则，任何一条都不能妥协：
 * <ol>
 *   <li><b>单源超时隔离</b>：一个源卡住只浪费它自己的 timeout，不影响其它源；</li>
 *   <li><b>全局 deadline</b>：所有源合计不超过 150ms，到点就取消未完成的 future；</li>
 *   <li><b>至少一个源成功</b>：全部失败或全部为空时走 Redis/DB 热门兜底，接口不报错。</li>
 * </ol>
 *
 * <p>线程池用 {@code SynchronousQueue + AbortPolicy}：池满时直接拒绝该源，
 * 而不是把请求排进队列里等到超时——排队等待在 150ms 的预算里没有意义。
 */
@Slf4j
@Service
public class RecallEngine {

    private static final String FALLBACK_SOURCE = "fallback_hot";

    private final List<RecallSource> sources;
    private final RecConfigService configService;
    private final RecUpgradeProperties properties;
    private final RecMetricsService metricsService;
    private final RecallDataService dataService;
    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor executor;

    public RecallEngine(List<RecallSource> sources, RecConfigService configService,
                        RecUpgradeProperties properties, RecMetricsService metricsService,
                        RecallDataService dataService, RedisOps redisOps, ObjectMapper objectMapper,
                        @Qualifier("recallExecutor") ThreadPoolExecutor executor) {
        this.sources = sources;
        this.configService = configService;
        this.properties = properties;
        this.metricsService = metricsService;
        this.dataService = dataService;
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public List<RecallResult> recall(RecallContext ctx) {
        long entryStart = System.currentTimeMillis();
        if (ctx.getDeadlineMs() <= 0) {
            ctx.setDeadlineMs(entryStart + properties.getRecallDeadlineMs());
        }
        RecConfigService.RecallSetting setting = configService.recall(ctx.getScene());
        Map<String, RecConfigService.SourceSetting> byName = new LinkedHashMap<>();
        if (setting != null && setting.getSources() != null) {
            for (RecConfigService.SourceSetting item : setting.getSources()) {
                if (item != null && item.getName() != null) {
                    byName.put(item.getName(), item);
                }
            }
        }

        List<RecallSource> runnable = new ArrayList<>();
        for (RecallSource source : sources) {
            RecConfigService.SourceSetting sourceSetting = byName.get(source.name());
            // 配置里显式列出的源以配置为准；没列出的源默认开启，避免新增源忘记加配置就静默失效
            boolean configured = sourceSetting == null || sourceSetting.isEnabled();
            if (!configured) {
                continue;
            }
            if (source.requiresUser() && !ctx.hasUser()) {
                continue;
            }
            try {
                if (!source.enabled(ctx)) {
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("召回源 enabled() 异常 source={} cause={}", source.name(), e.toString());
                continue;
            }
            runnable.add(source);
        }
        runnable.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        Map<String, CompletableFuture<RecallResult>> futures = new LinkedHashMap<>();
        Map<String, Long> startedAt = new LinkedHashMap<>();
        for (RecallSource source : runnable) {
            long now = System.currentTimeMillis();
            if (now >= ctx.getDeadlineMs()) {
                metricsService.recordRecall(source.name(), false, 0, 0, "GLOBAL_DEADLINE");
                futures.put(source.name(), CompletableFuture.completedFuture(
                        RecallResult.failed(source.name(), 0, "GLOBAL_DEADLINE")));
                continue;
            }
            RecConfigService.SourceSetting sourceSetting = byName.get(source.name());
            int limit = sourceSetting == null || sourceSetting.getLimit() <= 0
                    ? properties.getRecallSourceLimit() : sourceSetting.getLimit();
            RecallContext sourceCtx = ctx.withLimit(limit);
            startedAt.put(source.name(), now);
            try {
                futures.put(source.name(), CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        RecallResult result = source.recall(sourceCtx);
                        if (result == null) {
                            return RecallResult.ok(source.name(), Collections.<RecallItem>emptyList(),
                                    System.currentTimeMillis() - start);
                        }
                        result.setSource(source.name());
                        result.setCostMs(System.currentTimeMillis() - start);
                        result.setSuccess(true);
                        return result;
                    } catch (RuntimeException e) {
                        return RecallResult.failed(source.name(), System.currentTimeMillis() - start,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }, executor));
            } catch (RejectedExecutionException e) {
                futures.put(source.name(), CompletableFuture.completedFuture(
                        RecallResult.failed(source.name(), 0, "REJECTED")));
            }
        }

        List<RecallResult> results = new ArrayList<>(futures.size());
        int successCount = 0;
        int itemCount = 0;
        for (Map.Entry<String, CompletableFuture<RecallResult>> entry : futures.entrySet()) {
            String name = entry.getKey();
            long remaining = ctx.getDeadlineMs() - System.currentTimeMillis();
            CompletableFuture<RecallResult> future = entry.getValue();
            RecallResult result;
            if (future.isDone() || remaining <= 0) {
                try {
                    result = future.getNow(RecallResult.failed(name, 0, "GLOBAL_DEADLINE"));
                } catch (RuntimeException e) {
                    result = RecallResult.failed(name, 0, "GLOBAL_DEADLINE");
                }
            } else {
                RecallSource source = find(runnable, name);
                int sourceTimeout = source == null ? properties.getSourceDefaultTimeoutMs()
                        : source.timeoutMs();
                RecConfigService.SourceSetting sourceSetting = byName.get(name);
                if (sourceSetting != null && sourceSetting.getTimeoutMs() != null) {
                    sourceTimeout = sourceSetting.getTimeoutMs();
                }
                long waitMs = Math.max(1, Math.min(sourceTimeout, remaining));
                try {
                    result = future.get(waitMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    result = RecallResult.failed(name,
                            System.currentTimeMillis() - startedAt.getOrDefault(name, System.currentTimeMillis()),
                            "TIMEOUT");
                } catch (ExecutionException e) {
                    result = RecallResult.failed(name, 0,
                            e.getCause() == null ? "EXECUTION_ERROR" : e.getCause().getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    result = RecallResult.failed(name, 0, "INTERRUPTED");
                }
            }
            if (result == null) {
                result = RecallResult.failed(name, 0, "NULL_RESULT");
            }
            results.add(result);
            if (result.isSuccess()) {
                successCount++;
                itemCount += result.size();
            }
            metricsService.recordRecall(name, result.isSuccess(), result.getCostMs(), result.size(),
                    result.getErrorMsg());
        }

        // 全部失败，或全部成功但都为空：走兜底热门（EX-24-001-5）
        if (successCount == 0 || itemCount == 0) {
            RecallResult fallback = fallbackHot(ctx);
            if (fallback != null) {
                results.add(fallback);
                metricsService.recordRecall(FALLBACK_SOURCE, true, fallback.getCostMs(),
                        fallback.size(), null);
            }
        }
        log.debug("召回完成 scene={} trace={} sources={} success={} items={} cost={}ms",
                ctx.getScene(), ctx.getTraceId(), results.size(), successCount, itemCount,
                System.currentTimeMillis() - entryStart);
        return results;
    }

    /**
     * 兜底热门池：优先读 Redis 快照（预热任务写入），Redis 不可用时直接扫可播曲库。
     * 这条路径必须几乎不会失败，所以不依赖任何外部服务。
     */
    private RecallResult fallbackHot(RecallContext ctx) {
        long start = System.currentTimeMillis();
        List<Long> ids = new ArrayList<>();
        try {
            java.util.Optional<String> cached = redisOps.get(RedisKeys.REC_FALLBACK_HOT);
            if (cached.isPresent() && !cached.get().isEmpty()) {
                ids = objectMapper.readValue(cached.get(), new TypeReference<List<Long>>() {
                });
            }
        } catch (Exception e) {
            log.debug("兜底热门缓存读取失败：{}", e.toString());
        }
        if (ids.isEmpty()) {
            for (Song song : dataService.playableSongs()) {
                ids.add(song.getId());
                if (ids.size() >= 200) {
                    break;
                }
            }
            try {
                redisOps.set(RedisKeys.REC_FALLBACK_HOT, objectMapper.writeValueAsString(ids),
                        Duration.ofMinutes(10));
            } catch (Exception e) {
                log.debug("兜底热门缓存写入失败：{}", e.toString());
            }
        }
        Set<Long> excludeIds = ctx.getExcludeIds() == null ? Collections.emptySet() : ctx.getExcludeIds();
        Set<Long> excludeArtists = ctx.getExcludeArtists() == null ? Collections.emptySet() : ctx.getExcludeArtists();
        Map<Long, Song> playable = dataService.playableMap();
        List<RecallItem> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        double denom = Math.max(1, ids.size());
        for (int i = 0; i < ids.size() && items.size() < Math.max(1, ctx.getLimit()); i++) {
            Long songId = ids.get(i);
            Song song = playable.get(songId);
            if (song == null || !seen.add(songId) || excludeIds.contains(songId)
                    || (song.getArtistId() != null && excludeArtists.contains(song.getArtistId()))) {
                continue;
            }
            items.add(RecallItem.of(songId, 1.0 - i / denom));
        }
        return RecallResult.ok(FALLBACK_SOURCE, items, System.currentTimeMillis() - start);
    }

    private RecallSource find(List<RecallSource> list, String name) {
        for (RecallSource source : list) {
            if (source.name().equals(name)) {
                return source;
            }
        }
        return null;
    }

    /** 当前已注册的召回源名字，供后台「召回健康度」页面展示。 */
    public Set<String> registeredSources() {
        Set<String> names = new LinkedHashSet<>();
        for (RecallSource source : sources) {
            names.add(source.name());
        }
        return names;
    }
}
