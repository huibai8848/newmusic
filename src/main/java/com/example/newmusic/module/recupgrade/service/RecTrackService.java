package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.dto.RecEventRequest;
import com.example.newmusic.module.recupgrade.entity.RecEvent;
import com.example.newmusic.module.recupgrade.mapper.RecEventMapper;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐埋点与链路归因（FR-24-009 / BR-24-092）。
 *
 * <p>事件表是实验指标的唯一口径：写入 {@code t_rec_event} 的同时累加按天分键的
 * Redis 计数（供实时看板），两者都是从同一份 {@link RecEvent} 落下去的，
 * 避免「看板和实验报告对不上」。
 *
 * <p>客户端只回传 {@code traceId}，实验分组由服务端从
 * {@code ml:rec:trace:{traceId}} 反查——分组必须以服务端为准，
 * 否则改一个请求参数就能把自己塞进对照组（BR-24-086）。
 */
@Slf4j
@Service
public class RecTrackService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long TRACE_TTL_SECONDS = 3600;
    private static final Duration METRIC_TTL = Duration.ofDays(8);

    /** EX-24-009-2：非法事件类型直接丢弃，不写库。 */
    private static final Set<String> ALLOWED_EVENTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(RecEvent.IMPRESSION, RecEvent.CLICK, RecEvent.PLAY, RecEvent.VALID_PLAY,
                    RecEvent.COMPLETE, RecEvent.SKIP, RecEvent.LIKE, RecEvent.DISLIKE,
                    RecEvent.EXPLORE_IMPRESSION, RecEvent.NEW_SONG_EXPOSURE)));

    private final RecEventMapper eventMapper;
    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;

    public RecTrackService(RecEventMapper eventMapper, RedisOps redisOps, ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
    }

    // ================================================================== 链路快照

    /**
     * 保存一次推荐的分组与位置快照。TTL 1 小时——埋点通常在会话内回传，
     * 留一小时足够，也避免共享 Redis 被无用链路占满。
     */
    public void saveTrace(RecallContext ctx, Map<String, String> experiments,
                          List<Candidate> finalList, String modelVersion, boolean degraded) {
        if (ctx == null || ctx.getTraceId() == null) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("traceId", ctx.getTraceId());
        snapshot.put("userId", ctx.getUserId());
        snapshot.put("deviceId", ctx.getDeviceId());
        snapshot.put("scene", ctx.getScene());
        snapshot.put("experiments", experiments == null ? Collections.emptyMap() : experiments);
        snapshot.put("modelVersion", modelVersion);
        snapshot.put("degraded", degraded);
        snapshot.put("coldStartStage", ctx.getColdStartStage());
        snapshot.put("createTime", LocalDateTime.now().toString());
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (finalList != null) {
            for (int i = 0; i < finalList.size(); i++) {
                Candidate candidate = finalList.get(i);
                if (candidate != null && candidate.getSongId() != null) {
                    positions.put(String.valueOf(candidate.getSongId()), i + 1);
                }
            }
        }
        snapshot.put("positions", positions);
        try {
            redisOps.set(RedisKeys.recTrace(ctx.getTraceId()),
                    objectMapper.writeValueAsString(snapshot), Duration.ofSeconds(TRACE_TTL_SECONDS));
        } catch (Exception e) {
            // 快照失败不影响推荐本身；埋点退化为「无实验归因」，仍然计数
            log.warn("推荐链路快照写入失败 traceId={} cause={}", ctx.getTraceId(), e.toString());
        }
    }

    public Map<String, Object> trace(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return Collections.emptyMap();
        }
        return redisOps.get(RedisKeys.recTrace(traceId)).map(raw -> {
            try {
                return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return Collections.<String, Object>emptyMap();
            }
        }).orElse(Collections.<String, Object>emptyMap());
    }

    // ================================================================== 埋点

    public void record(Long userId, RecEventRequest request) {
        if (request == null || request.getTraceId() == null || request.getTraceId().trim().isEmpty()) {
            return;
        }
        String type = request.getEventType() == null ? "" : request.getEventType().trim().toUpperCase();
        if (!ALLOWED_EVENTS.contains(type)) {
            log.debug("忽略非法推荐事件 type={} traceId={}", type, request.getTraceId());
            return;
        }
        Map<String, Object> snapshot = trace(request.getTraceId());
        RecEvent event = new RecEvent();
        event.setTraceId(request.getTraceId());
        event.setUserId(userId);
        event.setDeviceId(firstNonEmpty(request.getDeviceId(), stringOf(snapshot.get("deviceId"))));
        event.setScene(firstNonEmpty(request.getScene(), stringOf(snapshot.get("scene")), "HOME_FEED"));
        event.setEventType(type);
        event.setSongId(request.getSongId());
        event.setPosition(resolvePosition(request, snapshot));
        event.setPlayDuration(request.getPlayDuration() == null ? 0 : Math.max(0, request.getPlayDuration()));
        event.setUserType(firstNonEmpty(request.getUserType(), userId == null ? "GUEST" : "ACTIVE"));
        event.setDeviceType(request.getDeviceType() == null ? "" : request.getDeviceType().trim().toUpperCase());
        event.setHourOfDay(LocalDateTime.now().getHour());
        event.setCreateTime(LocalDateTime.now());

        Map<String, String> experiments = experimentsOf(snapshot);
        // 一条事件只能挂一个实验主键（表结构如此）；取第一个分组写主键，其余进 Redis 计数
        String experimentKey = "";
        String groupName = "";
        if (!experiments.isEmpty()) {
            Map.Entry<String, String> first = experiments.entrySet().iterator().next();
            experimentKey = first.getKey();
            groupName = first.getValue();
        }
        event.setExperimentKey(experimentKey);
        event.setGroupName(groupName);

        try {
            eventMapper.insert(event);
        } catch (RuntimeException e) {
            // EX-24-009-1：埋点写失败不能反噬用户操作
            log.warn("推荐事件写入失败 traceId={} type={} cause={}", request.getTraceId(), type, e.toString());
        }
        countMetrics(event, experiments);
        applyFeedback(userId, type, request.getSongId());
    }

    /** 实时计数：每个实验各记一笔，实验报告不依赖「主键只能一个」的限制。 */
    private void countMetrics(RecEvent event, Map<String, String> experiments) {
        String date = LocalDate.now().format(DATE);
        if (experiments.isEmpty()) {
            incrementMetric(date, event.getScene(), "", "", event.getEventType());
            return;
        }
        for (Map.Entry<String, String> entry : experiments.entrySet()) {
            incrementMetric(date, event.getScene(), entry.getKey(), entry.getValue(), event.getEventType());
        }
    }

    private void incrementMetric(String date, String scene, String experimentKey, String group, String type) {
        String key = RedisKeys.recMetric(date, scene == null ? "HOME_FEED" : scene,
                experimentKey == null ? "" : experimentKey, group == null ? "" : group);
        redisOps.hashIncrement(key, type, 1L, METRIC_TTL);
        redisOps.hashIncrement(key, "TOTAL", 1L, METRIC_TTL);
        if (RecEvent.IMPRESSION.equals(type) || RecEvent.EXPLORE_IMPRESSION.equals(type)) {
            redisOps.expire(key, METRIC_TTL);
        }
    }

    /** 负反馈进 30 天集合，正反馈把它撤掉；探索与排序都会读这个集合。 */
    private void applyFeedback(Long userId, String type, Long songId) {
        if (userId == null || songId == null) {
            return;
        }
        if (RecEvent.DISLIKE.equals(type)) {
            redisOps.addToSet(RedisKeys.recNegative(userId), String.valueOf(songId), Duration.ofDays(30));
        } else if (RecEvent.LIKE.equals(type) || RecEvent.PLAY.equals(type)) {
            redisOps.removeFromSet(RedisKeys.recNegative(userId), String.valueOf(songId));
        }
    }

    private Map<String, String> experimentsOf(Map<String, Object> snapshot) {
        Object raw = snapshot.get("experiments");
        if (!(raw instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private Integer resolvePosition(RecEventRequest request, Map<String, Object> snapshot) {
        if (request.getPosition() != null) {
            return request.getPosition();
        }
        Object raw = snapshot.get("positions");
        if (raw instanceof Map && request.getSongId() != null) {
            Object position = ((Map<?, ?>) raw).get(String.valueOf(request.getSongId()));
            if (position instanceof Number) {
                return ((Number) position).intValue();
            }
        }
        return null;
    }

    private String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

}
