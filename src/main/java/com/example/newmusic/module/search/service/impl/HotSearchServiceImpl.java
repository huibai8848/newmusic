package com.example.newmusic.module.search.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.example.newmusic.infra.cache.CacheService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.entity.SearchHot;
import com.example.newmusic.module.search.mapper.SearchHotMapper;
import com.example.newmusic.module.search.service.HotSearchService;
import com.example.newmusic.module.search.support.KeywordSupport;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.search.vo.HotSearchVO;
import com.example.newmusic.module.search.vo.HotWordVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class HotSearchServiceImpl implements HotSearchService {

    /** 增速标记的阈值（BR-15-033）。PRD 只给了「爆 / 热」两个词，没有给数值 */
    private static final double BURST_GROWTH = 5d;
    private static final double HOT_GROWTH = 3d;

    /** 重建榜单时从 ZSet 里取的候选数。取 50 而不是 10：前 10 里可能有一半是敏感词或纯符号，
     *  取满 50 才够筛出 10 条。 */
    private static final long CANDIDATE_LIMIT = 50L;

    private final RedisOps redisOps;
    private final CacheService cacheService;
    private final SearchHotMapper hotMapper;
    private final SearchProperties properties;
    private final SensitiveWordSupport sensitiveWordSupport;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public HotSearchServiceImpl(RedisOps redisOps, CacheService cacheService, SearchHotMapper hotMapper,
                                SearchProperties properties, SensitiveWordSupport sensitiveWordSupport,
                                ObjectMapper objectMapper,
                                @Qualifier("searchExecutor") Executor executor) {
        this.redisOps = redisOps;
        this.cacheService = cacheService;
        this.hotMapper = hotMapper;
        this.properties = properties;
        this.sensitiveWordSupport = sensitiveWordSupport;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    // ======================= 计数 =======================

    @Override
    public void recordAsync(String keyword, String subject) {
        String normalized = KeywordSupport.normalize(keyword);
        if (!KeywordSupport.isDisplayable(normalized)) {
            // 单字符、纯符号、空白：它们进榜只会占位置（BR-15-029 的过滤在**入口**也做一遍，
            // 不是只在展示时做——让它们进 ZSet 再筛掉，等于让刷榜者用垃圾词把真词挤下去）
            return;
        }
        // BR-15-027：敏感词命中的请求本来就在 Controller 之前被拒了，但热搜计数是异步的、
        // 且可能被其它入口调用，这里再判一次，不依赖「上游一定拦住」
        if (sensitiveWordSupport.isBlocked(normalized)) {
            return;
        }
        try {
            executor.execute(() -> increment(normalized, subject));
        } catch (RejectedExecutionException e) {
            log.debug("热搜计数任务被拒绝（应用关闭中）keyword={}", normalized);
        }
    }

    private void increment(String keyword, String subject) {
        // EX-15-005-1 去刷榜：同一主体、同一词、1 分钟内只计一次。
        // 用 SETNX 而不是「先 GET 再 SET」：后者在两个并发请求之间必然漏计或重复计。
        Duration dedup = Duration.ofSeconds(properties.getHot().getDedupSeconds());
        String dedupKey = RedisKeys.searchHotDedup(subject, DigestUtil.md5Hex(keyword));
        if (!redisOps.setIfAbsent(dedupKey, "1", dedup)) {
            return;
        }
        redisOps.zIncrBy(RedisKeys.SEARCH_HOT_REALTIME, keyword, 1d,
                Duration.ofSeconds(properties.getHot().getRealtimeTtlSeconds()));
    }

    // ======================= 展示 =======================

    @Override
    public HotSearchVO snapshot() {
        CacheService.Lookup<HotSearchVO> cached =
                cacheService.get(RedisKeys.SEARCH_HOT_SNAPSHOT, HotSearchVO.class);
        if (cached.isHit()) {
            return cached.value();
        }
        HotSearchVO rebuilt;
        try {
            rebuilt = rebuild();
        } catch (Exception e) {
            // 重建失败（Redis + MySQL 同时不可用）：退回兜底词，而不是让首页的热搜区 500。
            // 热搜是「锦上添花」的模块，它挂掉不该影响任何主流程
            log.error("热搜榜重建失败，退回兜底词", e);
            rebuilt = defaultsSnapshot();
        }
        cacheService.put(RedisKeys.SEARCH_HOT_SNAPSHOT, rebuilt,
                Duration.ofSeconds(properties.getHot().getSnapshotTtlSeconds()));
        return rebuilt;
    }

    @Override
    public List<HotWordVO> cachedWords() {
        CacheService.Lookup<HotSearchVO> cached =
                cacheService.get(RedisKeys.SEARCH_HOT_SNAPSHOT, HotSearchVO.class);
        return cached.isHit() ? cached.value().getWords() : Collections.<HotWordVO>emptyList();
    }

    @Override
    public List<String> plainWords() {
        List<HotWordVO> words = snapshot().getWords();
        List<String> plain = new ArrayList<>(words.size());
        for (HotWordVO word : words) {
            plain.add(word.getWord());
        }
        return plain;
    }

    private HotSearchVO rebuild() {
        Map<String, Double> realtime =
                redisOps.zRevRangeWithScores(RedisKeys.SEARCH_HOT_REALTIME, 0L, CANDIDATE_LIMIT - 1);
        if (realtime.isEmpty()) {
            // EX-15-005-2：ZSet 丢了（重启没持久化 / 新环境首启）→ 从最近一天的库表重建
            realtime = loadFromDatabase();
        }
        if (realtime.isEmpty()) {
            // EX-15-005-3：库里也没有 → 用配置的兜底词
            return defaultsSnapshot();
        }

        Map<String, Double> last = readLastSnapshot();
        List<HotWordVO> words = new ArrayList<>(properties.getHot().getSize());
        int rank = 0;
        for (Map.Entry<String, Double> entry : realtime.entrySet()) {
            String word = entry.getKey();
            if (!isRankable(word)) {
                continue;
            }
            rank++;
            if (rank > properties.getHot().getSize()) {
                break;
            }
            HotWordVO vo = new HotWordVO();
            vo.setWord(word);
            vo.setRank(rank);
            vo.setMark(computeMark(word, entry.getValue(), last));
            vo.setIsTop(false);
            words.add(vo);
        }

        List<HotWordVO> withTops = prependTops(words);
        writeLastSnapshot(realtime);

        HotSearchVO snapshot = new HotSearchVO();
        snapshot.setWords(withTops);
        snapshot.setRefreshedAt(LocalDateTime.now());
        return snapshot;
    }

    /** 展示过滤（BR-15-029）：敏感词、纯符号、单字符都不上墙 */
    private boolean isRankable(String word) {
        return KeywordSupport.isDisplayable(word) && !sensitiveWordSupport.isBlocked(word);
    }

    /**
     * 增速标记（BR-15-033）。
     *
     * <p>「新」用 SETNX 判定：这个词在 24 小时内**第一次**进入榜单。
     * 用 SETNX 而不是「查 last 快照里有没有」——last 的 TTL 只有 1 小时，
     * 一个词在榜上待够 1 小时后就会从 last 里消失，于是被反复标成「新」。
     */
    private String computeMark(String word, double count, Map<String, Double> last) {
        boolean firstSeen = redisOps.setIfAbsent(RedisKeys.searchHotSeen(word), "1",
                Duration.ofSeconds(properties.getHot().getSeenTtlSeconds()));
        if (firstSeen) {
            return "新";
        }
        Double previous = last.get(word);
        if (previous == null) {
            return null;
        }
        double growth = count - previous;
        if (growth > BURST_GROWTH) {
            return "爆";
        }
        if (growth > HOT_GROWTH) {
            return "热";
        }
        return null;
    }

    /**
     * 运营置顶词插到最前（BR-15-030），最多 3 个，且**从榜中去重**——
     * 否则置顶词会在榜上出现两次（第 1 名和第 7 名），看起来像个 bug。
     */
    private List<HotWordVO> prependTops(List<HotWordVO> words) {
        List<SearchHot> tops;
        try {
            tops = hotMapper.selectTops(LocalDate.now(), properties.getHot().getTopMax());
        } catch (Exception e) {
            log.warn("读取置顶词失败，本次无置顶 cause={}", e.toString());
            return words;
        }
        if (tops == null || tops.isEmpty()) {
            return words;
        }
        Set<String> topWords = new HashSet<>();
        List<HotWordVO> result = new ArrayList<>(tops.size() + words.size());
        for (SearchHot top : tops) {
            String word = top.getKeyword();
            if (!StringUtils.hasText(word) || !topWords.add(word)) {
                continue;
            }
            HotWordVO vo = new HotWordVO();
            vo.setWord(word);
            vo.setMark(null);
            vo.setIsTop(true);
            result.add(vo);
        }
        for (HotWordVO word : words) {
            if (topWords.contains(word.getWord())) {
                continue;
            }
            // 排名以**最终**顺序为准（置顶词在最前），所以插完置顶再统一编号
            result.add(word);
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
        return result;
    }

    private Map<String, Double> loadFromDatabase() {
        List<SearchHot> rows = hotMapper.selectLatestRanked(properties.getHot().getSize());
        Map<String, Double> result = new LinkedHashMap<>();
        if (rows != null) {
            for (SearchHot row : rows) {
                if (row.getKeyword() != null) {
                    result.put(row.getKeyword(),
                            row.getSearchCount() == null ? 0d : row.getSearchCount().doubleValue());
                }
            }
        }
        return result;
    }

    private HotSearchVO defaultsSnapshot() {
        List<HotWordVO> words = new ArrayList<>();
        int rank = 0;
        for (String word : properties.getHot().getDefaults()) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            HotWordVO vo = new HotWordVO();
            vo.setWord(word);
            vo.setRank(++rank);
            vo.setMark(null);
            vo.setIsTop(false);
            words.add(vo);
        }
        HotSearchVO snapshot = new HotSearchVO();
        snapshot.setWords(words);
        snapshot.setRefreshedAt(LocalDateTime.now());
        return snapshot;
    }

    // ======================= 上一份快照（算增速用） =======================

    /**
     * 上一份快照的「词 → 计数」。
     *
     * <p>走 {@link RedisOps} + 应用自己的 ObjectMapper，而不是 {@link CacheService}：
     * 这份数据只在**重建榜单时**读一次（5 分钟一次），享受不到 L1 本地缓存的好处，
     * 而 CacheService 的 L1 会把它变成一份需要关心失效的额外状态。
     */
    private Map<String, Double> readLastSnapshot() {
        Optional<String> raw = redisOps.get(RedisKeys.SEARCH_HOT_LAST);
        if (!raw.isPresent() || !StringUtils.hasText(raw.get())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(raw.get(), new TypeReference<Map<String, Double>>() {
            });
        } catch (Exception e) {
            log.warn("上一份热搜快照解析失败，本次无增速标记 cause={}", e.toString());
            return Collections.emptyMap();
        }
    }

    private void writeLastSnapshot(Map<String, Double> counts) {
        try {
            Map<String, Double> trimmed = new LinkedHashMap<>();
            int kept = 0;
            for (Map.Entry<String, Double> entry : counts.entrySet()) {
                if (kept++ >= properties.getHot().getSize()) {
                    break;
                }
                trimmed.put(entry.getKey(), entry.getValue());
            }
            redisOps.set(RedisKeys.SEARCH_HOT_LAST, objectMapper.writeValueAsString(trimmed),
                    Duration.ofSeconds(properties.getHot().getLastTtlSeconds()));
        } catch (Exception e) {
            log.warn("写上一份热搜快照失败（只影响下次的增速标记）cause={}", e.toString());
        }
    }

    // ======================= 整点落库 =======================

    @Override
    @Scheduled(cron = "${newmusic.search.hot.persist-cron:0 5 * * * ?}")
    public int persistDaily() {
        // 注意：这里刻意不读展示快照，而是直接读实时 ZSet。
        // 快照是「给用户看的、5 分钟前的一份」，落库要的是「当前的真实累计值」
        Map<String, Double> realtime =
                redisOps.zRevRangeWithScores(RedisKeys.SEARCH_HOT_REALTIME, 0L, properties.getHot().getSize() - 1L);
        if (realtime.isEmpty()) {
            log.info("热搜落库：实时榜为空，跳过");
            return 0;
        }
        LocalDate today = LocalDate.now();
        int rank = 0;
        int written = 0;
        for (Map.Entry<String, Double> entry : realtime.entrySet()) {
            rank++;
            if (!isRankable(entry.getKey())) {
                continue;
            }
            try {
                written += hotMapper.upsert(IdUtil.getSnowflakeNextId(), entry.getKey(),
                        entry.getValue().longValue(), rank, today);
            } catch (Exception e) {
                log.warn("热搜落库单条失败 keyword={} cause={}", entry.getKey(), e.toString());
            }
        }
        log.info("热搜落库完成 date={} 条数={}", today, written);
        return written;
    }
}
