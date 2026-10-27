package com.example.newmusic.module.ai.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用配额（FR-23-001 限流）。
 *
 * <p>四个维度，缺一不可：
 * <ul>
 *   <li><b>用户每小时 / 每天</b>：防止单个账号刷歌词翻译。次数写在 {@link AiScene} 枚举里。</li>
 *   <li><b>全局 QPS</b>：保护上游账单与下游服务，与 {@code @RateLimit} 的滑动窗口不同，
 *       这里只做粗粒度计数——AI 调用本来就要几百毫秒，秒级计数足够。</li>
 *   <li><b>场景 QPS</b>：大模型场景（歌单生成）不能和翻译共用一个额度。</li>
 * </ul>
 *
 * <p><b>缓存命中不计数。</b>限流的目的是控制真实上游调用；命中缓存的请求既没有成本
 * 也没有延迟，把它算进额度只会让用户觉得「明明秒出还说我用超了」。因此配额检查
 * 放在缓存之后、Provider 调用之前。
 *
 * <p>离线任务（userId == null）跳过用户与 QPS 限制：批处理是系统行为，
 * 它自己由 {@code annotationMaxPerRun} 与 cron 控制节奏。
 *
 * <p>Redis 不可用时一律放行。配额是成本护栏，不是安全边界。
 */
@Slf4j
@Component
public class AiQuotaService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisOps redisOps;
    private final AiProperties properties;

    public AiQuotaService(RedisOps redisOps, AiProperties properties) {
        this.redisOps = redisOps;
        this.properties = properties;
    }

    /** 调用前检查；超限抛 60002。 */
    public void check(AiScene scene, Long userId) {
        if (userId == null) {
            return;
        }
        checkQps(scene);

        if (scene.getUserLimitPerHour() > 0) {
            long used = redisOps.increment(hourKey(userId, scene), untilNextHour());
            if (used > scene.getUserLimitPerHour()) {
                throw new BizException(ResultCode.AI_QUOTA_EXCEEDED,
                        "本小时该功能已使用 " + scene.getUserLimitPerHour() + " 次，请稍后再试");
            }
        }
        if (scene.getUserLimitPerDay() > 0) {
            long used = redisOps.increment(dayKey(userId, scene), untilMidnight());
            if (used > scene.getUserLimitPerDay()) {
                throw new BizException(ResultCode.AI_QUOTA_EXCEEDED,
                        "今日该功能已使用 " + scene.getUserLimitPerDay() + " 次，请明日再试");
            }
        }
    }

    /** 给前端展示「今日还剩几次」。 */
    public Map<String, Object> quota(Long userId, AiScene scene) {
        long hourLimit = scene.getUserLimitPerHour();
        long dayLimit = scene.getUserLimitPerDay();
        long hourUsed = hourLimit <= 0 ? 0 : read(hourKey(userId, scene));
        long dayUsed = dayLimit <= 0 ? 0 : read(dayKey(userId, scene));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scene", scene.code());
        view.put("hourLimit", hourLimit);
        view.put("hourUsed", hourUsed);
        view.put("hourRemaining", hourLimit <= 0 ? -1 : Math.max(0, hourLimit - hourUsed));
        view.put("dayLimit", dayLimit);
        view.put("dayUsed", dayUsed);
        view.put("dayRemaining", dayLimit <= 0 ? -1 : Math.max(0, dayLimit - dayUsed));
        return view;
    }

    public List<Map<String, Object>> quotaAll(Long userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiScene scene : AiScene.values()) {
            if (scene.getUserLimitPerHour() > 0 || scene.getUserLimitPerDay() > 0) {
                list.add(quota(userId, scene));
            }
        }
        return list;
    }

    private void checkQps(AiScene scene) {
        long second = Instant.now().getEpochSecond();
        String globalKey = RedisKeys.AI_RATE_GLOBAL + second;
        long global = redisOps.increment(globalKey, Duration.ofSeconds(2));
        if (global > properties.getGlobalQps()) {
            throw new BizException(ResultCode.AI_QUOTA_EXCEEDED, "AI 服务繁忙，请稍后再试");
        }
        String sceneKey = RedisKeys.AI_RATE_SCENE + scene.code() + ":" + second;
        long sceneQps = redisOps.increment(sceneKey, Duration.ofSeconds(2));
        if (sceneQps > properties.getSceneQps()) {
            throw new BizException(ResultCode.AI_QUOTA_EXCEEDED, "AI 服务繁忙，请稍后再试");
        }
    }

    private String hourKey(Long userId, AiScene scene) {
        return RedisKeys.AI_RATE + userId + ":" + scene.code() + ":h:" + hourStamp();
    }

    private String dayKey(Long userId, AiScene scene) {
        return RedisKeys.AI_RATE + userId + ":" + scene.code() + ":d:" + LocalDate.now().format(DAY);
    }

    private String hourStamp() {
        LocalDateTime now = LocalDateTime.now();
        return now.toLocalDate().format(DAY) + now.getHour();
    }

    private long read(String key) {
        try {
            return redisOps.get(key).map(Long::parseLong).orElse(0L);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Duration untilNextHour() {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1);
        return Duration.between(LocalDateTime.now(), now);
    }

    private Duration untilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
    }
}
