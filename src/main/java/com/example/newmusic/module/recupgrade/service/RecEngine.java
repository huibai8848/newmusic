package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.dto.RecFeedRequest;
import com.example.newmusic.module.recupgrade.entity.RecLog;
import com.example.newmusic.module.recupgrade.experiment.ExperimentService;
import com.example.newmusic.module.recupgrade.mapper.RecLogMapper;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecTrace;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.rank.CoarseRanker;
import com.example.newmusic.module.recupgrade.rank.FineRanker;
import com.example.newmusic.module.recupgrade.rank.RankResult;
import com.example.newmusic.module.recupgrade.rank.RerankService;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.recall.RecallEngine;
import com.example.newmusic.module.recupgrade.vo.RecFeedItemVO;
import com.example.newmusic.module.recupgrade.vo.RecFeedVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 推荐主链路编排（FR-24-001 ~ FR-24-004）。
 *
 * <pre>
 * 上下文 → 召回(15 源并发) → 融合(WRF/Top500) → 粗排(150) → 精排 → 重排(30) → 曝光
 * </pre>
 *
 * <p><b>失败降级是逐段独立的</b>：召回失败用兜底热门，精排失败用粗排分，重排失败直接截断。
 * 任何一段都不允许把异常抛给调用方——首页推荐挂掉比「推荐得不够准」严重得多。
 *
 * <p>所有阶段耗时、数量、实验分组、模型版本都写进 {@link RecTrace}：
 * 一半进 {@code t_rec_log} 供离线分析，一半进 Redis 快照供埋点归因。
 */
@Slf4j
@Service
public class RecEngine {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SCENE_DEFAULT = "HOME_FEED";

    private final RecallEngine recallEngine;
    private final FusionService fusionService;
    private final CoarseRanker coarseRanker;
    private final FineRanker fineRanker;
    private final RerankService rerankService;
    private final NewSongService newSongService;
    private final ColdStartService coldStartService;
    private final ExperimentService experimentService;
    private final RecTrackService trackService;
    private final RecMetricsService metricsService;
    private final RecallDataService dataService;
    private final RecLogMapper logMapper;
    private final RedisOps redisOps;
    private final RecUpgradeProperties properties;
    private final ObjectMapper objectMapper;

    public RecEngine(RecallEngine recallEngine, FusionService fusionService,
                     CoarseRanker coarseRanker, FineRanker fineRanker,
                     RerankService rerankService, NewSongService newSongService,
                     ColdStartService coldStartService, ExperimentService experimentService,
                     RecTrackService trackService, RecMetricsService metricsService,
                     RecallDataService dataService,
                     RecLogMapper logMapper, RedisOps redisOps,
                     RecUpgradeProperties properties, ObjectMapper objectMapper) {
        this.recallEngine = recallEngine;
        this.fusionService = fusionService;
        this.coarseRanker = coarseRanker;
        this.fineRanker = fineRanker;
        this.rerankService = rerankService;
        this.newSongService = newSongService;
        this.coldStartService = coldStartService;
        this.experimentService = experimentService;
        this.trackService = trackService;
        this.metricsService = metricsService;
        this.dataService = dataService;
        this.logMapper = logMapper;
        this.redisOps = redisOps;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RecFeedVO feed(Long userId, RecFeedRequest request) {
        long start = System.currentTimeMillis();
        RecFeedRequest req = request == null ? new RecFeedRequest() : request;
        String scene = normalizeScene(req.getScene());
        int limit = clampLimit(req.getLimit());

        RecTrace trace = new RecTrace();
        trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        trace.setScene(scene);
        trace.setUserId(userId);

        RecallContext ctx = buildContext(userId, req, scene, trace);
        Map<String, String> experiments = experimentService.assign(userId, req.getDeviceId());
        trace.setExperiments(experiments);
        if (!experiments.isEmpty()) {
            ctx.setExperimentBucket(experiments.values().iterator().next());
        }

        long stageStart = System.currentTimeMillis();
        List<RecallResult> recallResults = recallEngine.recall(ctx);
        trace.timing("recall", System.currentTimeMillis() - stageStart);
        trace.setRecallResults(recallResults);
        trace.count("recall", countItems(recallResults));

        stageStart = System.currentTimeMillis();
        List<Candidate> fused = fusionService.fuse(scene, recallResults);
        trace.timing("fusion", System.currentTimeMillis() - stageStart);
        trace.setFusedCount(fused.size());
        trace.count("fusion", fused.size());

        stageStart = System.currentTimeMillis();
        List<Candidate> coarse = coarseRanker.rank(scene, fused, ctx);
        trace.timing("coarse", System.currentTimeMillis() - stageStart);
        trace.setCoarseCount(coarse.size());
        trace.count("coarse", coarse.size());

        RankResult rankResult;
        List<Candidate> ranked;
        try {
            rankResult = fineRanker.rank(scene, coarse, ctx);
            if (rankResult == null || rankResult.getCandidates().isEmpty()) {
                rankResult = RankResult.of(sortedByCoarse(coarse), "rule-lr-v1", "COARSE_FALLBACK", 0);
                trace.degrade("FINE_EMPTY");
            }
        } catch (RuntimeException e) {
            // EX-24-003-4：模型异常退回粗排分，仍然返回推荐
            trace.degrade("FINE_FAILED");
            trace.getWarnings().add("FINE_FAILED:" + e.getMessage());
            log.warn("精排失败，回退粗排 traceId={} cause={}", trace.getTraceId(), e.toString());
            rankResult = RankResult.of(sortedByCoarse(coarse), "rule-lr-v1", "COARSE_FALLBACK", 0);
        }
        ranked = rankResult.getCandidates();
        trace.setFineCount(ranked.size());
        trace.count("fine", ranked.size());
        trace.setModelVersion(rankResult.getModelVersion());
        trace.timing("fine", rankResult.getCostMs());

        List<Candidate> exposable = filterNewSong(ctx, ranked, trace);

        List<Candidate> finalList;
        try {
            finalList = rerankService.rerank(scene, exposable, ctx, trace);
        } catch (RuntimeException e) {
            trace.degrade("RERANK_FAILED");
            trace.getWarnings().add("RERANK_FAILED:" + e.getMessage());
            log.warn("重排失败，直接截断 traceId={} cause={}", trace.getTraceId(), e.toString());
            finalList = new ArrayList<>(exposable.subList(0, Math.min(limit, exposable.size())));
        }
        finalList = ensureSize(finalList, exposable, limit);
        trace.count("final", finalList.size());

        newSongService.recordExposure(ctx, finalList);
        for (Candidate candidate : finalList) {
            trace.getFinalSongIds().add(candidate.getSongId());
        }

        long cost = System.currentTimeMillis() - start;
        trace.setTotalCostMs(cost);
        trace.timing("total", cost);
        for (String reason : trace.getDegraded()) {
            metricsService.recordDegrade(reason);
        }
        trackService.saveTrace(ctx, experiments, finalList, rankResult.getModelVersion(),
                !trace.getDegraded().isEmpty());
        writeLog(trace, ctx, fused.size(), rankResult, finalList, cost);

        return toVo(trace, ctx, finalList, experiments, rankResult, cost);
    }

    // ------------------------------------------------------------------ 上下文

    private RecallContext buildContext(Long userId, RecFeedRequest req, String scene, RecTrace trace) {
        RecallContext ctx = new RecallContext();
        ctx.setTraceId(trace.getTraceId());
        ctx.setUserId(userId);
        ctx.setDeviceId(req.getDeviceId());
        ctx.setScene(scene);
        ctx.setSeedSongId(req.getSeedSongId());
        ctx.setSeedPlaylistId(req.getSeedPlaylistId());
        ctx.setLimit(properties.getRecallSourceLimit());
        ctx.setDeadlineMs(System.currentTimeMillis() + properties.getRecallDeadlineMs());
        ctx.setProfile(dataService.profile(userId));
        coldStartService.apply(ctx);
        trace.setSubject(ctx.subject());

        LocalDateTime now = LocalDateTime.now();
        ctx.getFeatures().put("hour", now.getHour());
        ctx.getFeatures().put("weekday", now.getDayOfWeek().getValue());
        ctx.getFeatures().put("deviceType", req.getDeviceType());

        if (userId != null) {
            ctx.setRecentSongIds(dataService.recentSongIds(userId, 50));
            Set<Long> exclude = new LinkedHashSet<>();
            exclude.addAll(dataService.recentSongIds(userId, 200));
            exclude.addAll(toLongs(redisOps.members(RedisKeys.recDislike(userId))));
            exclude.addAll(toLongs(redisOps.members(RedisKeys.recNegative(userId))));
            ctx.setExcludeIds(exclude);
            ctx.setExcludeArtists(toLongs(redisOps.members(RedisKeys.recDislikeArtist(userId))));
            ctx.setImpressedSongIds(toLongs(redisOps.members(
                    RedisKeys.recExposure(userId, LocalDate.now().format(DATE)))));
        }
        if (ctx.getProfile() != null) {
            ctx.getExcludeIds().addAll(ctx.getProfile().getListenedSongIds());
        }
        return ctx;
    }

    // ------------------------------------------------------------------ 后处理

    /** 新歌扶持闸门：达到曝光上限/完成率过低的歌在重排前剔除。 */
    private List<Candidate> filterNewSong(RecallContext ctx, List<Candidate> input, RecTrace trace) {
        List<Candidate> result = new ArrayList<>(input.size());
        int dropped = 0;
        for (Candidate candidate : input) {
            if (newSongService.canExpose(ctx, candidate)) {
                result.add(candidate);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            trace.count("newSongDropped", dropped);
        }
        return result;
    }

    /** 重排可能把列表裁到 30 以下（如去重后不足），从精排候选里补齐。 */
    private List<Candidate> ensureSize(List<Candidate> finalList, List<Candidate> pool, int limit) {
        List<Candidate> result = new ArrayList<>(finalList);
        if (result.size() >= limit) {
            return result.size() == limit ? result : new ArrayList<>(result.subList(0, limit));
        }
        Set<Long> ids = new HashSet<>();
        for (Candidate candidate : result) {
            ids.add(candidate.getSongId());
        }
        for (Candidate candidate : pool) {
            if (result.size() >= limit) {
                break;
            }
            if (candidate.getSongId() != null && ids.add(candidate.getSongId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<Candidate> sortedByCoarse(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingDouble(Candidate::getCoarseScore).reversed()
                .thenComparing(Candidate::getSongId));
        return result;
    }

    private int countItems(List<RecallResult> results) {
        int total = 0;
        if (results != null) {
            for (RecallResult result : results) {
                total += result.getItems() == null ? 0 : result.getItems().size();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ 输出

    private RecFeedVO toVo(RecTrace trace, RecallContext ctx, List<Candidate> finalList,
                           Map<String, String> experiments, RankResult rankResult, long cost) {
        RecFeedVO vo = new RecFeedVO();
        vo.setTraceId(trace.getTraceId());
        vo.setScene(trace.getScene());
        vo.setDegraded(!trace.getDegraded().isEmpty());
        vo.setColdStartStage(ctx.getColdStartStage());
        vo.setExperiments(experiments);
        vo.setModelVersion(rankResult.getModelVersion());
        vo.setCostMs(cost);
        vo.setTimings(trace.getTimings());
        List<RecFeedItemVO> items = new ArrayList<>(finalList.size());
        int position = 1;
        for (Candidate candidate : finalList) {
            RecFeedItemVO item = new RecFeedItemVO();
            item.setPosition(position++);
            item.setSong(candidate.getSong() != null
                    ? candidate.getSong() : dataService.findPlayable(candidate.getSongId()));
            item.setReason(reason(candidate, ctx));
            item.setScore(candidate.getFineScore());
            item.setSources(new LinkedHashMap<>(candidate.getSources()));
            item.setExplored(candidate.isExplored());
            item.setEditor(candidate.isEditor());
            item.setNewSong(candidate.isNewSong());
            item.setLongTail(candidate.isLongTail());
            items.add(item);
        }
        vo.setList(items);
        return vo;
    }

    /** 理由要能对上来源；冷启动阶段不能假装「根据你的收听习惯」。 */
    private String reason(Candidate candidate, RecallContext ctx) {
        if (candidate.isExplored()) {
            return "换个口味";
        }
        if (candidate.isEditor()) {
            return "编辑精选";
        }
        if (candidate.isNewSong()) {
            return "新歌首发";
        }
        Map<String, Double> sources = candidate.getSources();
        if (sources != null) {
            if (sources.containsKey("friend_listen")) {
                return "好友在听";
            }
            if (sources.containsKey("follow_artist")) {
                return "你关注的歌手";
            }
            if (sources.containsKey("tag_match")) {
                return "符合你的口味";
            }
            if (sources.containsKey("cf_user") || sources.containsKey("cf_item")
                    || sources.containsKey("vector") || sources.containsKey("vector_song")) {
                return "根据你的收听习惯";
            }
            if (sources.containsKey("playlist_match")) {
                return "来自你的歌单";
            }
        }
        return coldStartService.reason(ctx.getColdStartStage());
    }

    private void writeLog(RecTrace trace, RecallContext ctx, int candidateCount,
                          RankResult rankResult, List<Candidate> finalList, long cost) {
        try {
            RecLog logRow = new RecLog();
            logRow.setTraceId(trace.getTraceId());
            logRow.setUserId(ctx.getUserId());
            logRow.setDeviceId(ctx.getDeviceId());
            logRow.setScene(trace.getScene());
            logRow.setCandidateCount(candidateCount);
            logRow.setModelVersion(rankResult.getModelVersion());
            logRow.setFeatureVersion(trace.getFeatureVersion());
            logRow.setCostMs(toJson(trace.getTimings()));
            logRow.setDegraded(trace.getDegraded().isEmpty() ? 0 : 1);
            logRow.setDegradeReason(String.join(",", trace.getDegraded()));
            logRow.setExperimentJson(toJson(trace.getExperiments()));
            logRow.setRecallResult(toJson(recallSummary(trace)));
            logRow.setFinalList(toJson(trace.getFinalSongIds()));
            logRow.setCreateTime(LocalDateTime.now());
            logMapper.insert(logRow);
        } catch (RuntimeException e) {
            // 日志写失败不影响推荐
            log.warn("推荐日志写入失败 traceId={} cause={}", trace.getTraceId(), e.toString());
        }
    }

    private Map<String, Object> recallSummary(RecTrace trace) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (RecallResult result : trace.getRecallResults()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("success", result.isSuccess());
            row.put("size", result.getItems() == null ? 0 : result.getItems().size());
            row.put("costMs", result.getCostMs());
            if (result.getErrorMsg() != null) {
                row.put("error", result.getErrorMsg());
            }
            summary.put(result.getSource(), row);
        }
        return summary;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Set<Long> toLongs(Set<String> values) {
        Set<Long> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            try {
                result.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过
            }
        }
        return result;
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.trim().isEmpty()) {
            return SCENE_DEFAULT;
        }
        return scene.trim().toUpperCase(Locale.ROOT);
    }

    private int clampLimit(Integer limit) {
        int value = limit == null ? properties.getFinalSize() : limit;
        return Math.max(1, Math.min(value, properties.getMaxRequestSize()));
    }
}
