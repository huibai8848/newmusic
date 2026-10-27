package com.example.newmusic.module.ai.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ai.config.AiScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 成本预算守卫（FR-23-001 / BR-23-004）。
 *
 * <p>三层限制：
 * <ol>
 *   <li><b>日预算</b> {@code ai.daily.budget.cent}：用尽即暂停全部 AI 调用，直到次日零点；</li>
 *   <li><b>场景预算</b> {@code ai.scene.budget.cent}：某场景烧太快时可以先掐它，不影响其它场景；</li>
 *   <li><b>阈值告警</b> {@code ai.budget.alert.percent}：到 80% 记一次告警键并打 WARN，不阻断。</li>
 * </ol>
 *
 * <p><b>预算是「先查后花」，不是精确扣减。</b>并发请求可能同时通过检查、一起超额一点点；
 * 要做到严格不超支得在 Redis 里做 Lua 预留（先扣预估成本，调用完再按实际多退少补）。
 * 对「日预算 300 元」这个量级，超额一次调用的成本可以忽略，而 Lua 预留会显著增加故障面。
 * 这里选择简单、可解释的方案，并在文档里写明。
 *
 * <p>Redis 不可用时预算**放行**（{@link RedisOps} 读回 0）：AI 是增强能力，
 * 不能因为计数器故障反而把主流程卡死。此时成本日志仍会落库，不会丢账。
 */
@Slf4j
@Component
public class AiBudgetGuard {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisOps redisOps;
    private final AiConfigService configService;

    public AiBudgetGuard(RedisOps redisOps, AiConfigService configService) {
        this.redisOps = redisOps;
        this.configService = configService;
    }

    /** 调用前检查；超预算抛 60002。 */
    public void ensureAvailable(AiScene scene) {
        if (redisOps.get(RedisKeys.AI_BUDGET_PAUSED).isPresent()) {
            throw new BizException(ResultCode.AI_QUOTA_EXCEEDED, "今日 AI 预算已用完，功能将在明日恢复");
        }
        long dailyBudget = configService.dailyBudgetCent();
        if (dailyBudget > 0 && usedTodayCent() >= dailyBudget) {
            pause("daily");
            throw new BizException(ResultCode.AI_QUOTA_EXCEEDED, "今日 AI 预算已用完，功能将在明日恢复");
        }
        long sceneBudget = configService.sceneBudgetCent(scene);
        if (sceneBudget > 0 && usedTodayCent(scene) >= sceneBudget) {
            throw new BizException(ResultCode.AI_QUOTA_EXCEEDED, "该功能今日额度已用完，请明日再试");
        }
    }

    /** 调用成功后记账（分）。缓存命中不调用本方法。 */
    public void record(AiScene scene, int costCent) {
        if (costCent <= 0) {
            return;
        }
        String today = today();
        Duration untilMidnight = untilMidnight();
        long total = redisOps.incrementBy(RedisKeys.AI_COST + today, costCent, untilMidnight);
        redisOps.incrementBy(RedisKeys.AI_COST + today + ":" + scene.code(), costCent, untilMidnight);
        alertIfNeeded(today, total);
    }

    public long usedTodayCent() {
        return readCent(RedisKeys.AI_COST + today());
    }

    public long usedTodayCent(AiScene scene) {
        return readCent(RedisKeys.AI_COST + today() + ":" + scene.code());
    }

    public boolean isPaused() {
        return redisOps.get(RedisKeys.AI_BUDGET_PAUSED).isPresent();
    }

    public boolean isAlerted() {
        return redisOps.get(RedisKeys.AI_BUDGET_ALERT + today()).isPresent();
    }

    /** 后台调高预算后清掉暂停标志，让功能立刻恢复，不必等到明天。 */
    public void clearPaused() {
        redisOps.delete(RedisKeys.AI_BUDGET_PAUSED);
    }

    public Map<String, Object> state() {
        long used = usedTodayCent();
        long budget = configService.dailyBudgetCent();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", today());
        view.put("usedCent", used);
        view.put("dailyBudgetCent", budget);
        view.put("usedPercent", budget <= 0 ? 0 : Math.min(100, Math.round(used * 100.0 / budget)));
        view.put("alertPercent", configService.alertPercent());
        view.put("alerted", isAlerted());
        view.put("paused", isPaused());
        return view;
    }

    private void alertIfNeeded(String today, long total) {
        long budget = configService.dailyBudgetCent();
        if (budget <= 0) {
            return;
        }
        int percent = configService.alertPercent();
        if (percent <= 0 || total * 100 < budget * percent) {
            return;
        }
        String key = RedisKeys.AI_BUDGET_ALERT + today;
        // setIfAbsent：并发调用下只告警一次
        if (redisOps.setIfAbsent(key, String.valueOf(total), untilMidnight())) {
            log.warn("AI 日预算告警：已用 {} 分 / {} 分（{}%）", total, budget, total * 100 / budget);
        }
    }

    private void pause(String reason) {
        redisOps.set(RedisKeys.AI_BUDGET_PAUSED, reason, untilMidnight());
        log.warn("AI 预算用尽，已暂停调用 reason={}", reason);
    }

    private long readCent(String key) {
        try {
            return redisOps.get(key).map(Long::parseLong).orElse(0L);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String today() {
        return LocalDate.now().format(DAY);
    }

    private Duration untilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
    }
}
