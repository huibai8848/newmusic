package com.example.newmusic.module.ai.gateway;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.TraceIdHolder;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import com.example.newmusic.module.ai.entity.AiCallLog;
import com.example.newmusic.module.ai.provider.AiProvider;
import com.example.newmusic.module.ai.provider.AiProviderResult;
import com.example.newmusic.module.ai.provider.AiProviderRouter;
import com.example.newmusic.module.ai.service.AiCostService;
import com.example.newmusic.module.ai.support.AiBudgetGuard;
import com.example.newmusic.module.ai.support.AiConfigService;
import com.example.newmusic.module.ai.support.AiContentAudit;
import com.example.newmusic.module.ai.support.AiQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 网关默认实现，顺序严格按 PRD：
 * <b>指纹 → 缓存 → 预算 → 限流 → 路由 → 调用（超时/重试/降级）→ 审核 → 写缓存 → 记成本</b>。
 *
 * <p>几个容易做反的顺序，这里明确一下：
 * <ul>
 *   <li><b>缓存先于预算与限流</b>：命中缓存既不花钱也不占上游容量，没有理由因为它
 *       先把用户拦下来。预算用尽时，已缓存的结果仍然可用。</li>
 *   <li><b>审核先于写缓存</b>：违规内容一旦进了永久缓存，事后清理要逐个键删。</li>
 *   <li><b>结构化解析先于写缓存</b>：解析失败的原始文本如果被缓存，后续每次都会继续失败。</li>
 *   <li><b>降级只针对调用失败 / 解析失败</b>：预算、限流、内容审核不降级——
 *       换个更小的模型并不能解决「预算用完」或「输出违规」。</li>
 * </ul>
 *
 * <p>每次调用都写一条 {@code t_ai_call_log}，包括缓存命中（成本 0、{@code from_cache=1}）。
 * 不记缓存命中就统计不出命中率；而命中率是这个模块省钱与否的唯一直接指标。
 */
@Slf4j
@Service
@SuppressWarnings("unchecked")
public class DefaultAiGateway implements AiGateway {

    private static final int MAX_ATTEMPTS_PER_LEVEL = 2;
    private static final int MAX_ERROR_LEN = 500;

    private final AiProperties properties;
    private final AiConfigService configService;
    private final AiBudgetGuard budgetGuard;
    private final AiQuotaService quotaService;
    private final AiContentAudit contentAudit;
    private final AiProviderRouter providerRouter;
    private final RedisOps redisOps;
    private final AiCostService costService;
    private final ObjectMapper objectMapper;

    public DefaultAiGateway(AiProperties properties, AiConfigService configService,
                            AiBudgetGuard budgetGuard, AiQuotaService quotaService,
                            AiContentAudit contentAudit, AiProviderRouter providerRouter,
                            RedisOps redisOps, AiCostService costService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.configService = configService;
        this.budgetGuard = budgetGuard;
        this.quotaService = quotaService;
        this.contentAudit = contentAudit;
        this.providerRouter = providerRouter;
        this.redisOps = redisOps;
        this.costService = costService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiResponse<String> chat(AiRequest request) {
        Execution execution = execute(request, false, null);
        return AiResponse.<String>builder()
                .content(execution.content)
                .fromCache(execution.fromCache)
                .degraded(execution.degraded)
                .model(execution.model)
                .modelLevel(execution.level.name())
                .promptVersion(execution.promptVersion)
                .inputTokens(execution.inputTokens)
                .outputTokens(execution.outputTokens)
                .costCent(execution.costCent)
                .latencyMs(execution.latencyMs)
                .build();
    }

    @Override
    public <T> AiResponse<T> chatStructured(AiRequest request, Class<T> clazz) {
        Execution execution = execute(request, true, clazz);
        return AiResponse.<T>builder()
                .data((T) execution.data)
                .content(execution.content)
                .fromCache(execution.fromCache)
                .degraded(execution.degraded)
                .model(execution.model)
                .modelLevel(execution.level.name())
                .promptVersion(execution.promptVersion)
                .inputTokens(execution.inputTokens)
                .outputTokens(execution.outputTokens)
                .costCent(execution.costCent)
                .latencyMs(execution.latencyMs)
                .build();
    }

    @Override
    public AiResponse<float[]> embed(String text, String scene) {
        if (!StringUtils.hasText(text)) {
            throw new BizException(ResultCode.PARAM_INVALID, "向量化文本不能为空");
        }
        try {
            float[] vector = providerRouter.provider().embed(text);
            return AiResponse.<float[]>builder().data(vector).model("embedding").build();
        } catch (UnsupportedOperationException e) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "向量化能力尚未接入");
        }
    }

    // =========================================================================

    private Execution execute(AiRequest request, boolean structured, Class<?> clazz) {
        long started = System.currentTimeMillis();
        AiScene scene = requireScene(request);
        if (!properties.isEnabled()) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 功能暂未开启");
        }
        if (!StringUtils.hasText(request.getUserPrompt())) {
            throw new BizException(ResultCode.PARAM_INVALID, "AI 请求内容不能为空");
        }

        String system = request.getSystemPrompt() == null ? "" : request.getSystemPrompt();
        String user = request.getUserPrompt();
        double temperature = request.getTemperature() == null ? 0.7d : request.getTemperature();
        int maxTokens = request.getMaxTokens() == null ? scene.getMaxTokens() : request.getMaxTokens();
        boolean jsonMode = structured || Boolean.TRUE.equals(request.getJsonMode());
        AiScene.Level initialLevel = request.getModelLevel() == null
                ? configService.levelOf(scene)
                : AiScene.Level.of(request.getModelLevel(), configService.levelOf(scene));

        String cacheKey = cacheKey(request, scene, providerRouter.modelOf(initialLevel),
                system, user, temperature, maxTokens, jsonMode);
        Duration cacheTtl = cacheTtl(request, scene);
        // 缓存总开关或场景 TTL 关闭时，即使算出了 key 也不能读/写——
        // 否则「ai.cache.enabled=false」只关掉了写、读仍然命中旧值，配置看起来没生效
        String effectiveCacheKey = cacheTtl == null ? null : cacheKey;

        if (effectiveCacheKey != null) {
            Execution cached = readCache(effectiveCacheKey, scene, structured, clazz,
                    System.currentTimeMillis() - started);
            if (cached != null) {
                writeLog(scene, cached, request, true, true, null, null);
                return cached;
            }
        }

        // 缓存之后才检查预算/配额：命中缓存不花钱、不占额度
        budgetGuard.ensureAvailable(scene);
        quotaService.check(scene, request.getUserId());

        AiProvider provider = providerRouter.provider();
        AiScene.Level level = initialLevel;
        boolean degraded = false;
        String lastModel = providerRouter.modelOf(level);
        RuntimeException lastError = null;

        while (level != null) {
            String model = providerRouter.modelOf(level);
            long timeoutMs = request.getTimeoutMs() == null ? scene.getTimeoutMs() : request.getTimeoutMs();
            AiRequest effective = AiRequest.builder()
                    .scene(request.getScene())
                    .systemPrompt(system)
                    .userPrompt(user)
                    .history(request.getHistory())
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeoutMs(timeoutMs)
                    .jsonMode(jsonMode)
                    .cacheKey(request.getCacheKey())
                    .cacheTtl(request.getCacheTtl())
                    .userId(request.getUserId())
                    .modelLevel(level.name())
                    .bizId(request.getBizId())
                    .build();

            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_LEVEL; attempt++) {
                lastModel = model;
                long callStarted = System.currentTimeMillis();
                try {
                    AiProviderResult result = provider.chat(effective, model, timeoutMs);
                    if (!StringUtils.hasText(result.getContent())) {
                        throw new IllegalStateException("Provider 返回空内容");
                    }
                    Object data = null;
                    if (structured) {
                        data = parse(result.getContent(), clazz);
                    }
                    int costCent = costService.estimateCostCent(level,
                            result.getInputTokens(), result.getOutputTokens());

                    if (contentAudit.isBlocked(result.getContent())) {
                        // 审核不通过：不缓存、不记成本，但要留下一条失败日志供追溯
                        BizException blocked = new BizException(ResultCode.AI_OUTPUT_ILLEGAL);
                        writeFailureLog(scene, model, level, request, degraded,
                                System.currentTimeMillis() - callStarted, blocked.getMessage());
                        throw blocked;
                    }

                    Execution execution = new Execution();
                    execution.content = result.getContent();
                    execution.data = data;
                    execution.model = result.getModel() == null ? model : result.getModel();
                    execution.level = level;
                    execution.promptVersion = scene.getPromptVersion();
                    execution.inputTokens = result.getInputTokens();
                    execution.outputTokens = result.getOutputTokens();
                    execution.costCent = costCent;
                    execution.fromCache = false;
                    execution.degraded = degraded;
                    execution.latencyMs = System.currentTimeMillis() - started;

                    budgetGuard.record(scene, costCent);
                    if (effectiveCacheKey != null) {
                        writeCache(effectiveCacheKey, execution, cacheTtl);
                    }
                    writeLog(scene, execution, request, false, true, null, null);
                    return execution;
                } catch (BizException e) {
                    if (e.getCode() == ResultCode.AI_OUTPUT_ILLEGAL.getCode()
                            || e.getCode() == ResultCode.AI_INPUT_ILLEGAL.getCode()) {
                        throw e;
                    }
                    lastError = e;
                    log.warn("AI 调用失败 scene={} level={} attempt={} cause={}",
                            scene.code(), level, attempt, e.getMessage());
                } catch (RuntimeException e) {
                    lastError = e;
                    log.warn("AI 调用异常 scene={} level={} attempt={} cause={}",
                            scene.code(), level, attempt, e.toString());
                }
            }

            AiScene.Level lower = level.lower();
            if (lower == null) {
                break;
            }
            degraded = true;
            log.warn("AI 降级 scene={} {} -> {}", scene.code(), level, lower);
            level = lower;
        }

        long elapsed = System.currentTimeMillis() - started;
        writeFailureLog(scene, lastModel, level == null ? initialLevel : level, request, degraded,
                elapsed, lastError == null ? "AI 调用失败" : lastError.getMessage());
        if (lastError instanceof BizException) {
            throw (BizException) lastError;
        }
        throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务暂时不可用，请稍后再试", lastError);
    }

    private AiScene requireScene(AiRequest request) {
        AiScene scene = AiScene.of(request.getScene());
        if (scene == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "未知 AI 场景：" + request.getScene());
        }
        return scene;
    }

    /**
     * 缓存时长。返回 {@code null} 表示不缓存；
     * {@link Duration#ZERO} 表示永久（{@link RedisOps#set} 对零时长不设 TTL）。
     */
    private Duration cacheTtl(AiRequest request, AiScene scene) {
        if (!configService.cacheEnabled()) {
            return null;
        }
        if (request.getCacheTtl() != null) {
            if (request.getCacheTtl().isZero() || request.getCacheTtl().isNegative()) {
                return null;
            }
            return request.getCacheTtl();
        }
        long seconds = scene.getCacheTtlSeconds();
        if (seconds == 0) {
            return null;
        }
        return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
    }

    private String cacheKey(AiRequest request, AiScene scene, String model, String system,
                            String user, double temperature, int maxTokens, boolean jsonMode) {
        if (StringUtils.hasText(request.getCacheKey())) {
            return RedisKeys.AI_CACHE + "custom:" + scene.code() + ":" + request.getCacheKey();
        }
        if (!scene.isCacheable()) {
            return null;
        }
        return RedisKeys.AI_CACHE + AiFingerprint.of(model, scene.getPromptVersion(),
                system, user, temperature, maxTokens, jsonMode);
    }

    private Execution readCache(String key, AiScene scene, boolean structured, Class<?> clazz,
                                long elapsed) {
        Optional<String> raw = redisOps.get(key);
        if (!raw.isPresent()) {
            return null;
        }
        try {
            CacheEntry entry = objectMapper.readValue(raw.get(), CacheEntry.class);
            if (!StringUtils.hasText(entry.getContent())) {
                redisOps.delete(key);
                return null;
            }
            Object data = structured ? parse(entry.getContent(), clazz) : null;
            Execution execution = new Execution();
            execution.content = entry.getContent();
            execution.data = data;
            execution.model = entry.getModel();
            execution.level = AiScene.Level.of(entry.getLevel(), scene.getDefaultLevel());
            execution.promptVersion = entry.getPromptVersion();
            execution.inputTokens = entry.getInputTokens();
            execution.outputTokens = entry.getOutputTokens();
            execution.costCent = 0;
            execution.fromCache = true;
            execution.degraded = entry.isDegraded();
            execution.latencyMs = elapsed;
            return execution;
        } catch (Exception e) {
            // 旧格式/损坏的缓存不能拖垮请求：删掉重算
            log.warn("AI 缓存解析失败，已丢弃 key={} cause={}", key, e.toString());
            redisOps.delete(key);
            return null;
        }
    }

    private void writeCache(String key, Execution execution, Duration ttl) {
        try {
            CacheEntry entry = new CacheEntry();
            entry.setContent(execution.content);
            entry.setModel(execution.model);
            entry.setLevel(execution.level.name());
            entry.setPromptVersion(execution.promptVersion);
            entry.setInputTokens(execution.inputTokens);
            entry.setOutputTokens(execution.outputTokens);
            entry.setDegraded(execution.degraded);
            redisOps.set(key, objectMapper.writeValueAsString(entry), ttl);
        } catch (RuntimeException e) {
            log.warn("AI 缓存写入失败 key={} cause={}", key, e.toString());
        } catch (Exception e) {
            log.warn("AI 缓存序列化失败 key={} cause={}", key, e.toString());
        }
    }

    private void writeLog(AiScene scene, Execution execution, AiRequest request,
                          boolean cacheHit, boolean success, String error, String trace) {
        AiCallLog entry = new AiCallLog();
        entry.setScene(scene.code());
        entry.setModel(execution.model);
        entry.setModelLevel(execution.level == null ? null : execution.level.name());
        entry.setPromptVersion(execution.promptVersion);
        entry.setUserId(request.getUserId());
        entry.setInputTokens(execution.inputTokens);
        entry.setOutputTokens(execution.outputTokens);
        entry.setCostCent(execution.costCent);
        entry.setLatencyMs((int) Math.min(Integer.MAX_VALUE, execution.latencyMs));
        entry.setFromCache(cacheHit ? 1 : 0);
        entry.setSuccess(success ? 1 : 0);
        entry.setDegraded(execution.degraded ? 1 : 0);
        entry.setErrorMsg(error);
        entry.setBizId(request.getBizId());
        entry.setTraceId(trace == null ? TraceIdHolder.current() : trace);
        costService.record(entry);
    }

    private void writeFailureLog(AiScene scene, String model, AiScene.Level level,
                                 AiRequest request, boolean degraded, long elapsedMs, String error) {
        AiCallLog entry = new AiCallLog();
        entry.setScene(scene.code());
        entry.setModel(model);
        entry.setModelLevel(level == null ? null : level.name());
        entry.setPromptVersion(scene.getPromptVersion());
        entry.setUserId(request.getUserId());
        entry.setInputTokens(0);
        entry.setOutputTokens(0);
        entry.setCostCent(0);
        entry.setLatencyMs((int) Math.min(Integer.MAX_VALUE, elapsedMs));
        entry.setFromCache(0);
        entry.setSuccess(0);
        entry.setDegraded(degraded ? 1 : 0);
        entry.setErrorMsg(truncate(error));
        entry.setBizId(request.getBizId());
        entry.setTraceId(TraceIdHolder.current());
        costService.record(entry);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LEN ? value : value.substring(0, MAX_ERROR_LEN);
    }

    /** 兼容模型常见输出：```json 围栏、前后解释文字。 */
    private <T> T parse(String content, Class<T> clazz) {
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("AI 返回不是合法 JSON");
        }
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private static final class Execution {
        private String content;
        private Object data;
        private String model;
        private AiScene.Level level;
        private String promptVersion;
        private int inputTokens;
        private int outputTokens;
        private int costCent;
        private boolean fromCache;
        private boolean degraded;
        private long latencyMs;
    }

    /** 缓存值。带上模型与档位，命中时才能如实回显。 */
    @lombok.Data
    public static class CacheEntry {
        private String content;
        private String model;
        private String level;
        private String promptVersion;
        private int inputTokens;
        private int outputTokens;
        private boolean degraded;
    }
}
