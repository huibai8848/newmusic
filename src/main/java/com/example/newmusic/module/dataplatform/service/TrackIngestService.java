package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.common.util.TraceIdHolder;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.dataplatform.config.DataPlatformExecutorConfig;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.dto.TrackBatchReq;
import com.example.newmusic.module.dataplatform.dto.TrackEventReq;
import com.example.newmusic.module.dataplatform.entity.EventDef;
import com.example.newmusic.module.dataplatform.entity.TrackDirty;
import com.example.newmusic.module.dataplatform.entity.TrackEvent;
import com.example.newmusic.module.dataplatform.mapper.EventDefMapper;
import com.example.newmusic.module.dataplatform.mapper.TrackDirtyMapper;
import com.example.newmusic.module.dataplatform.mapper.TrackEventMapper;
import com.example.newmusic.module.dataplatform.support.EventNameValidator;
import com.example.newmusic.module.dataplatform.support.IpRegionSupport;
import com.example.newmusic.module.dataplatform.support.TrackSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 埋点采集（FR-26-001 / FR-26-002）。
 *
 * <p>处理顺序刻意固定为：格式 → 限流 → 文档 → 幂等 → 采样 → 脱敏 → 落库 → 实时计数。
 * 顺序错了会出两类问题：先落库再脱敏等于把敏感信息写进了 ODS；先去重再校验会让
 * 「重复的非法事件」永远进不了脏数据表。
 *
 * <p>本机以 MySQL {@code t_track_event} 代替 Kafka；Kafka 不可用时的语义（不阻塞业务、
 * 失败可重试、脏数据隔离）在这里等价实现。
 */
@Slf4j
@Service
public class TrackIngestService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long DEF_CACHE_MILLIS = 60_000L;
    private static final long EVENT_TIME_DRIFT_MILLIS = 7L * 24 * 3600 * 1000;

    private final EventDefMapper eventDefMapper;
    private final TrackEventMapper trackEventMapper;
    private final TrackDirtyMapper trackDirtyMapper;
    private final RedisOps redisOps;
    private final ObjectMapper objectMapper;
    private final DataPlatformProperties properties;
    private final Executor executor;

    private volatile Map<String, EventDef> defCache = Collections.emptyMap();
    private volatile long defLoadedAt = 0L;

    public TrackIngestService(EventDefMapper eventDefMapper, TrackEventMapper trackEventMapper,
                              TrackDirtyMapper trackDirtyMapper, RedisOps redisOps,
                              ObjectMapper objectMapper, DataPlatformProperties properties,
                              @Qualifier(DataPlatformExecutorConfig.EXECUTOR) Executor executor) {
        this.eventDefMapper = eventDefMapper;
        this.trackEventMapper = trackEventMapper;
        this.trackDirtyMapper = trackDirtyMapper;
        this.redisOps = redisOps;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executor = executor;
    }

    public enum Outcome {
        ACCEPTED, DUPLICATE, DROPPED, DIRTY, SAMPLED, RATE_LIMITED
    }

    // ================================================================== 入口

    public Map<String, Object> ingestOne(TrackEventReq req, HttpServletRequest http) {
        Map<String, Integer> counters = newCounters();
        Outcome outcome = ingest(req, http, counters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome.name());
        result.put("accepted", counters.get("accepted"));
        result.put("duplicate", counters.get("duplicate"));
        result.put("sampled", counters.get("sampled"));
        result.put("dropped", counters.get("dropped"));
        return result;
    }

    public Map<String, Object> ingestBatch(TrackBatchReq batch, HttpServletRequest http) {
        if (batch == null || batch.getEvents() == null || batch.getEvents().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "events 不能为空");
        }
        if (batch.getEvents().size() > properties.getBatchMax()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "单批次最多 " + properties.getBatchMax() + " 条");
        }
        Map<String, Integer> counters = newCounters();
        for (TrackEventReq event : batch.getEvents()) {
            if (event == null) {
                counters.put("dropped", counters.get("dropped") + 1);
                continue;
            }
            if (isBlank(event.getDeviceId())) {
                event.setDeviceId(batch.getDeviceId());
            }
            if (isBlank(event.getAppVersion())) {
                event.setAppVersion(batch.getAppVersion());
            }
            ingest(event, http, counters);
        }
        Map<String, Object> result = new LinkedHashMap<>(counters);
        result.put("received", batch.getEvents().size());
        return result;
    }

    // ================================================================== 主流程

    private Outcome ingest(TrackEventReq req, HttpServletRequest http, Map<String, Integer> counters) {
        incrIngest("received");
        if (req == null) {
            dirty(null, null, null, "EMPTY_EVENT", counters);
            return Outcome.DIRTY;
        }

        String eventId = trim(req.getEventId());
        if (eventId == null || eventId.length() > 64) {
            dirty(null, resolveEventName(req), safeRaw(req), "INVALID_EVENT_ID", counters);
            return Outcome.DIRTY;
        }
        String eventName = resolveEventName(req);
        String nameError = EventNameValidator.validate(eventName);
        if (nameError != null) {
            dirty(eventId, eventName, safeRaw(req), "INVALID_EVENT_NAME: " + nameError, counters);
            return Outcome.DIRTY;
        }

        // 先限流再查文档：一台设备疯狂上报未登记事件时，不应把脏数据表打满
        String rateSubject = firstNonBlank(commonString(req, "deviceId"), IpUtil.getClientIp(http));
        if (!rateAllowed(rateSubject)) {
            counters.put("dropped", counters.get("dropped") + 1);
            incrIngest("rate_limited");
            return Outcome.RATE_LIMITED;
        }

        EventDef def = definition(eventName);
        if (def == null) {
            // BR-26-001：无文档的埋点不予采集
            dirty(eventId, eventName, safeRaw(req), "UNDOCUMENTED_EVENT", counters);
            return Outcome.DIRTY;
        }

        if (!dedupPass(eventId)) {
            counters.put("duplicate", counters.get("duplicate") + 1);
            incrIngest("duplicate");
            return Outcome.DUPLICATE;
        }

        if (!sampled(def, eventId)) {
            counters.put("sampled", counters.get("sampled") + 1);
            incrIngest("sampled");
            return Outcome.SAMPLED;
        }

        TrackEvent entity;
        try {
            entity = buildEntity(req, eventId, eventName, def, http);
        } catch (BizException e) {
            dirty(eventId, eventName, safeRaw(req), e.getMessage(), counters);
            return Outcome.DIRTY;
        }

        counters.put("accepted", counters.get("accepted") + 1);
        incrIngest("accepted");
        if (properties.isSyncPersist()) {
            persist(entity, counters);
        } else {
            executor.execute(() -> persist(entity, counters));
        }
        updateRealtime(entity);
        return Outcome.ACCEPTED;
    }

    private void persist(TrackEvent entity, Map<String, Integer> counters) {
        try {
            trackEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 幂等键兜底：Redis 故障时可能跳过 setIfAbsent，数据库唯一键是最后一道防线
            counters.put("duplicate", counters.get("duplicate") + 1);
            incrIngest("duplicate");
        } catch (Exception e) {
            counters.put("write_error", counters.get("write_error") + 1);
            incrIngest("write_error");
            log.warn("埋点落库失败 eventId={} event={} cause={}", entity.getEventId(),
                    entity.getEventName(), e.toString());
        }
    }

    // ================================================================== 校验与加工

    private TrackEvent buildEntity(TrackEventReq req, String eventId, String eventName,
                                   EventDef def, HttpServletRequest http) {
        Map<String, Object> rawProps = new LinkedHashMap<>();
        if (req.getCommon() != null) {
            rawProps.putAll(req.getCommon());
        }
        if (req.getProperties() != null) {
            rawProps.putAll(req.getProperties());
        }
        if (req.getProps() != null) {
            rawProps.putAll(req.getProps());
        }
        Map<String, Object> cleanProps = TrackSanitizer.sanitize(rawProps);

        long now = System.currentTimeMillis();
        Long clientTime = req.getEventTime();
        if (clientTime == null || clientTime < now - EVENT_TIME_DRIFT_MILLIS || clientTime > now + 3600_000L) {
            // BR-26-010：客户端时间不可信
            clientTime = now;
        }

        if ("player_song_end".equals(eventName) && !cleanProps.containsKey("is_valid_play")) {
            cleanProps.put("is_valid_play", isValidPlay(cleanProps));
        }

        String propsJson;
        try {
            propsJson = objectMapper.writeValueAsString(cleanProps);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "properties 无法序列化");
        }
        int propsBytes = propsJson.getBytes(StandardCharsets.UTF_8).length;
        if (propsBytes > properties.getMaxPropsBytes()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "PROPS_TOO_LARGE: 属性超过 " + properties.getMaxPropsBytes() + " 字节");
        }

        Long userId = UserContext.getUserId();
        String deviceId = trim(firstNonBlank(commonString(req, "deviceId"), req.getDeviceId()));
        String ip = IpUtil.getClientIp(http);

        TrackEvent entity = new TrackEvent();
        entity.setEventId(eventId);
        entity.setEventName(eventName);
        entity.setEventTime(clientTime);
        entity.setServerTime(LocalDateTime.now());
        entity.setUserId(userId);
        entity.setDeviceId(deviceId);
        entity.setSessionId(clip(commonString(req, "sessionId"), 64));
        entity.setPlatform(firstNonBlank(commonString(req, "platform"), "web"));
        entity.setAppVersion(clip(commonString(req, "appVersion"), 32));
        entity.setOs(clip(commonString(req, "os"), 64));
        entity.setBrowser(clip(commonString(req, "browser"), 64));
        entity.setScreen(clip(commonString(req, "screen"), 32));
        entity.setNetwork(clip(commonString(req, "network"), 16));
        entity.setIp(clip(ip, 45));
        entity.setRegion(IpRegionSupport.resolve(ip));
        entity.setPageId(clip(commonString(req, "pageId"), 64));
        entity.setPageUrl(clip(commonString(req, "pageUrl"), 512));
        entity.setReferrer(clip(commonString(req, "referrer"), 512));
        entity.setTraceId(clip(resolveTraceId(req), 64));
        entity.setAbTests(clip(toJson(commonValue(req, "abTests")), 500));
        entity.setIsNewUser(Boolean.TRUE.equals(req.getIsNewUser()) ? 1 : 0);
        entity.setProps(propsJson);
        entity.setDt(entity.getServerTime().toLocalDate());
        return entity;
    }

    // ================================================================== 实时指标

    /**
     * 实时计数与落库使用同一份实体：看板和离线口径若各自解析一遍 props，
     * 迟早会出现「实时 100、离线 98」这种无法解释的偏差。
     */
    private void updateRealtime(TrackEvent e) {
        String date = LocalDate.now().format(DAY);
        Duration ttl = Duration.ofDays(properties.getRealtimeTtlDays());
        // last_ts 是「覆盖写」而不是计数：用 HINCRBY 会让它变成时间戳累加
        redisOps.hashSetAll(RedisKeys.trackIngest(date),
                Collections.singletonMap("last_ts", String.valueOf(System.currentTimeMillis())));

        String subject = e.getUserId() != null ? "u:" + e.getUserId()
                : (e.getDeviceId() != null ? "d:" + e.getDeviceId() : null);
        if (subject != null) {
            redisOps.pfAdd(RedisKeys.stat("dau", date), subject, ttl);
            redisOps.zAdd(RedisKeys.stat("online", date), subject,
                    System.currentTimeMillis() / 1000.0, Duration.ofMinutes(10));
        }

        Map<String, Object> props = parseProps(e.getProps());
        String name = e.getEventName();
        if ("player_song_play".equals(name)) {
            redisOps.increment(RedisKeys.stat("play_cnt", date), ttl);
            String songId = str(props.get("song_id"));
            if (songId != null) {
                redisOps.zIncrBy(RedisKeys.stat("song:play", date), songId, 1, ttl);
            }
        } else if ("player_song_end".equals(name) || "player_valid_play".equals(name)) {
            boolean valid = name.equals("player_valid_play")
                    || Boolean.TRUE.equals(props.get("is_valid_play"))
                    || "true".equalsIgnoreCase(String.valueOf(props.get("is_valid_play")))
                    || "1".equals(String.valueOf(props.get("is_valid_play")));
            if (valid) {
                redisOps.increment(RedisKeys.stat("valid_play_cnt", date), ttl);
                redisOps.incrementBy(RedisKeys.stat("play_duration", date),
                        num(props.get("play_duration")), ttl);
                if (e.getUserId() != null) {
                    long count = redisOps.hashIncrement(RedisKeys.stat("dauplay:user", date),
                            String.valueOf(e.getUserId()), 1, ttl);
                    if (count >= properties.getDauPlayThreshold()) {
                        redisOps.pfAdd(RedisKeys.stat("dau_play", date), subject, ttl);
                    }
                }
            }
        } else if ("user_register_success".equals(name)) {
            redisOps.increment(RedisKeys.stat("new_user_cnt", date), ttl);
        } else if ("search_submit".equals(name)) {
            redisOps.increment(RedisKeys.stat("search_submit", date), ttl);
        } else if ("search_result_click".equals(name)) {
            redisOps.increment(RedisKeys.stat("search_result_click", date), ttl);
        } else if ("recommend_exposure".equals(name)) {
            redisOps.increment(RedisKeys.stat("recommend_exposure", date), ttl);
        } else if ("recommend_click".equals(name)) {
            redisOps.increment(RedisKeys.stat("recommend_click", date), ttl);
        } else if ("room_enter_success".equals(name)) {
            if (subject != null) {
                redisOps.pfAdd(RedisKeys.stat("room_user", date), subject, ttl);
            }
            String roomId = str(props.get("room_id"));
            if (roomId != null) {
                redisOps.zIncrBy(RedisKeys.stat("room_active", date), roomId, 1, ttl);
            }
        } else if ("post_like_click".equals(name) || "post_comment_submit".equals(name)
                || "user_follow_click".equals(name) || "post_publish_success".equals(name)) {
            if (subject != null) {
                redisOps.pfAdd(RedisKeys.stat("interaction_user", date), subject, ttl);
            }
        } else if ("ai_call".equals(name)) {
            long costCent = num(props.get("cost_cent"));
            redisOps.incrementBy(RedisKeys.stat("ai_cost_cent", date), costCent, ttl);
        }
    }

    // ================================================================== 工具

    public void reloadDefinitions() {
        Map<String, EventDef> fresh = new HashMap<>();
        List<EventDef> defs = eventDefMapper.selectList(
                Wrappers.<EventDef>lambdaQuery().eq(EventDef::getStatus, 1));
        for (EventDef def : defs) {
            fresh.put(def.getEventName(), def);
        }
        this.defCache = fresh;
        this.defLoadedAt = System.currentTimeMillis();
    }

    private EventDef definition(String eventName) {
        long now = System.currentTimeMillis();
        if (now - defLoadedAt > DEF_CACHE_MILLIS) {
            reloadDefinitions();
        }
        return defCache.get(eventName);
    }

    private boolean dedupPass(String eventId) {
        if (!redisOps.isAvailable()) {
            // Redis 故障时宁可放过重复，也不能把整条链路丢掉；DB 唯一键兜底
            return true;
        }
        return redisOps.setIfAbsent(RedisKeys.trackDedup(eventId), "1",
                Duration.ofSeconds(properties.getDedupSeconds()));
    }

    private boolean rateAllowed(String subject) {
        if (isBlank(subject) || properties.getRateLimitPerMinute() <= 0) {
            return true;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        String key = RedisKeys.trackRate(subject) + ':' + minute;
        long count = redisOps.increment(key, Duration.ofSeconds(70));
        return count <= properties.getRateLimitPerMinute();
    }

    /**
     * 采样只作用于「非核心 + 声明了采样」的事件（BR-26-008）。
     * 用 eventId 哈希取模，保证同一事件在重试时采样结果一致。
     */
    private boolean sampled(EventDef def, String eventId) {
        if (def.getIsSampled() == null || def.getIsSampled() != 1) {
            return true;
        }
        if (def.getPriority() != null && def.getPriority() == 1) {
            return true;
        }
        double rate = def.getSampleRate() == null ? 1.0 : def.getSampleRate().doubleValue();
        if (rate >= 1.0) {
            return true;
        }
        long bucket = Math.abs((long) eventId.hashCode() * 31L) % 10000;
        return bucket < (long) (rate * 10000);
    }

    private boolean isValidPlay(Map<String, Object> props) {
        Object flag = props.get("is_valid_play");
        if (Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag))
                || "1".equals(String.valueOf(flag))) {
            return true;
        }
        if (num(props.get("play_duration")) >= properties.getValidPlaySeconds()) {
            return true;
        }
        Object completion = props.get("completion_rate");
        if (completion != null) {
            try {
                return Double.parseDouble(String.valueOf(completion)) >= properties.getValidPlayRatio();
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private void dirty(String eventId, String eventName, String raw, String reason,
                       Map<String, Integer> counters) {
        counters.put("dropped", counters.get("dropped") + 1);
        incrIngest("dirty");
        try {
            TrackDirty dirty = new TrackDirty();
            dirty.setEventId(clip(eventId, 64));
            dirty.setEventName(clip(eventName, 64));
            dirty.setRaw(raw);
            dirty.setReason(reason.length() > 200 ? reason.substring(0, 200) : reason);
            trackDirtyMapper.insert(dirty);
        } catch (Exception e) {
            log.warn("脏数据写入失败 event={} reason={} cause={}", eventName, reason, e.toString());
        }
    }

    private String safeRaw(TrackEventReq req) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("eventId", req.getEventId());
        raw.put("event", resolveEventName(req));
        raw.put("eventTime", req.getEventTime());
        Map<String, Object> props = new LinkedHashMap<>();
        if (req.getCommon() != null) {
            props.putAll(req.getCommon());
        }
        if (req.getProperties() != null) {
            props.putAll(req.getProperties());
        }
        if (req.getProps() != null) {
            props.putAll(req.getProps());
        }
        raw.put("properties", TrackSanitizer.sanitize(props));
        return toJson(raw);
    }

    private void incrIngest(String field) {
        incrIngest(field, 1L);
    }

    private void incrIngest(String field, long delta) {
        redisOps.hashIncrement(RedisKeys.trackIngest(LocalDate.now().format(DAY)), field, delta,
                Duration.ofDays(properties.getRealtimeTtlDays()));
    }

    private Map<String, Integer> newCounters() {
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (String key : new String[]{"accepted", "duplicate", "sampled", "dropped", "write_error"}) {
            counters.put(key, 0);
        }
        return counters;
    }

    private String resolveEventName(TrackEventReq req) {
        return trim(firstNonBlank(req.getEvent(), req.getEventName()));
    }

    private String resolveTraceId(TrackEventReq req) {
        String mdc = MDC.get(TraceIdHolder.MDC_KEY);
        if (!isBlank(mdc)) {
            return mdc;
        }
        return firstNonBlank(req.getTraceId(), TraceIdHolder.current());
    }

    private Object commonValue(TrackEventReq req, String key) {
        if (req.getCommon() != null && req.getCommon().containsKey(key)) {
            return req.getCommon().get(key);
        }
        switch (key) {
            case "deviceId": return req.getDeviceId();
            case "sessionId": return req.getSessionId();
            case "platform": return req.getPlatform();
            case "appVersion": return req.getAppVersion();
            case "os": return req.getOs();
            case "browser": return req.getBrowser();
            case "screen": return req.getScreen();
            case "network": return req.getNetwork();
            case "pageId": return req.getPageId();
            case "pageUrl": return req.getPageUrl();
            case "referrer": return req.getReferrer();
            case "abTests": return req.getAbTests();
            default: return null;
        }
    }

    private String commonString(TrackEventReq req, String key) {
        return str(commonValue(req, key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProps(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static long num(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
