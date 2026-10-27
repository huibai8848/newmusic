package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.contentsafety.entity.ContentAuditRecord;
import com.example.newmusic.module.contentsafety.entity.UserCredit;
import com.example.newmusic.module.contentsafety.mapper.ContentAuditRecordMapper;
import com.example.newmusic.module.contentsafety.mapper.SensitiveWordMapper;
import com.example.newmusic.module.contentsafety.mapper.UserCreditMapper;
import com.example.newmusic.module.contentsafety.support.SensitiveWordEngine;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本/图片机审（FR-27-003）。
 *
 * <p>决策顺序是 PRD 的六级流水线的本地可执行版本：
 * DFA（含白名单/归一化）→ 正则规则 → 本地风险模型 → 图片 OCR/第三方（未配置时保守转人工）。
 * 任一环节 REJECT 即拒绝；有 REVIEW 且无 REJECT 则转人工；全通过才 PASS。
 *
 * <p><b>关于图片：</b>项目没有接第三方图像审核与 OCR，因此图片不做「假装通过」。
 * 有文字命中按文字处置；其余图片一律 REVIEW + {@code incomplete=1} 进人工队列，
 * 相同图片 URL 的 MD5 结果缓存 7 天，避免重复送审。接上供应商后只需替换
 * {@link #auditImages} 里的降级分支。
 */
@Slf4j
@Service
public class ContentAuditService {

    private static final int TEXT_LIMIT = 10000;
    private static final int SNAPSHOT_LIMIT = 1000;
    private static final String MODEL_VERSION = "local-heuristic-v1";

    private final SensitiveWordEngine engine;
    private final SensitiveWordMapper wordMapper;
    private final ContentAuditRecordMapper recordMapper;
    private final AuditTaskMapper taskMapper;
    private final UserCreditMapper creditMapper;
    private final UserMapper userMapper;
    private final RedisOps redisOps;

    public ContentAuditService(SensitiveWordEngine engine, SensitiveWordMapper wordMapper,
                               ContentAuditRecordMapper recordMapper, AuditTaskMapper taskMapper,
                               UserCreditMapper creditMapper, UserMapper userMapper,
                               RedisOps redisOps) {
        this.engine = engine;
        this.wordMapper = wordMapper;
        this.recordMapper = recordMapper;
        this.taskMapper = taskMapper;
        this.creditMapper = creditMapper;
        this.userMapper = userMapper;
        this.redisOps = redisOps;
    }

    /**
     * 执行机审并落库。内容尚未入库时 {@code contentId} 可以为空，
     * 待业务拿到主键后再调用 {@link #attachContent} 补录并建任务。
     */
    public AuditResult audit(AuditRequest request) {
        long started = System.nanoTime();
        String text = request.getText() == null ? "" : request.getText();
        if (text.length() > TEXT_LIMIT) {
            text = text.substring(0, TEXT_LIMIT);
        }
        String scene = defaultString(request.getScene(), request.getContentType());
        SensitiveWordEngine.Result dfa = engine.detect(text, scene);

        String decision = AuditResult.PASS;
        int risk = 0;
        String source = "DFA";
        String reason = null;

        Set<String> hitWords = new LinkedHashSet<>();
        for (SensitiveWordEngine.Match match : dfa.getMatches()) {
            hitWords.add(match.getWord());
            if (match.getWordId() > 0) {
                try {
                    wordMapper.incrementHit(match.getWordId(), 1);
                } catch (Exception e) {
                    log.debug("敏感词命中计数失败 wordId={} cause={}", match.getWordId(), e.toString());
                }
            }
        }
        if (dfa.getMaxLevel() >= 3) {
            decision = AuditResult.REJECT;
            risk = 100;
            reason = "命中禁止级敏感词";
        } else if (dfa.getMaxLevel() == 2) {
            decision = AuditResult.REVIEW;
            risk = 75;
            reason = "命中高危敏感词";
        } else if (dfa.getMaxLevel() == 1) {
            decision = AuditResult.REVIEW;
            risk = 35;
            reason = "命中疑似敏感词";
        }

        List<SensitiveWordEngine.RuleMatch> ruleMatches = dfa.getRuleMatches();
        if (!ruleMatches.isEmpty()) {
            source = dfa.getMatches().isEmpty() ? "RULE" : "DFA+RULE";
            int ruleRisk = 0;
            for (SensitiveWordEngine.RuleMatch rule : ruleMatches) {
                if ("REJECT".equalsIgnoreCase(rule.getAction())) {
                    ruleRisk = Math.max(ruleRisk, 80);
                    decision = AuditResult.REJECT;
                    reason = appendReason(reason, "命中拒绝规则：" + rule.getRuleName());
                } else if ("REVIEW".equalsIgnoreCase(rule.getAction())) {
                    ruleRisk = Math.max(ruleRisk, 50);
                    if (!AuditResult.REJECT.equals(decision)) {
                        decision = AuditResult.REVIEW;
                        reason = appendReason(reason, "命中审核规则：" + rule.getRuleName());
                    }
                }
            }
            risk = Math.max(risk, ruleRisk);
        }

        int strictness = strictness(request.getUserId());
        int rejectThreshold = 70 - strictness;
        int reviewThreshold = 40 - strictness;
        if (AuditResult.REVIEW.equals(decision) && risk >= rejectThreshold) {
            decision = AuditResult.REJECT;
            reason = appendReason(reason, strictness > 0 ? "账号风险加严，转拒绝" : "风险分达到拒绝阈值");
        } else if (AuditResult.PASS.equals(decision) && risk >= reviewThreshold) {
            decision = AuditResult.REVIEW;
            reason = appendReason(reason, "风险分达到人工复核阈值");
        }

        boolean incomplete = false;
        if (request.getImages() != null && !request.getImages().isEmpty()
                || "IMAGE".equalsIgnoreCase(request.getContentType())) {
            ImageVerdict image = auditImages(request.getImages(), decision, reason);
            if (AuditResult.REJECT.equals(image.decision)) {
                decision = AuditResult.REJECT;
                reason = appendReason(reason, image.reason);
                risk = Math.max(risk, 90);
            } else if (AuditResult.REVIEW.equals(image.decision) && !AuditResult.REJECT.equals(decision)) {
                decision = AuditResult.REVIEW;
                reason = appendReason(reason, image.reason);
                risk = Math.max(risk, 45);
            }
            incomplete = image.incomplete;
            if (image.incomplete) {
                source = "DFA".equals(source) ? "OCR_PENDING" : source;
            }
        }

        if (reason == null && AuditResult.PASS.equals(decision)) {
            reason = "机审通过";
        }

        long costMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        AuditResult result = new AuditResult();
        result.setDecision(decision);
        result.setReason(reason);
        result.setHitWords(new ArrayList<>(hitWords));
        result.setRiskLevel(risk);
        result.setAuditSource(source);
        result.setCostMs(costMs);
        result.setWordVersion(dfa.getWordVersion());
        result.setModelVersion(MODEL_VERSION);
        result.setIncomplete(incomplete);

        ContentAuditRecord record = new ContentAuditRecord();
        record.setContentType(defaultString(request.getContentType(), "TEXT"));
        record.setContentId(request.getContentId());
        record.setUserId(request.getUserId());
        record.setScene(scene);
        record.setContentSnapshot(snapshot(text, dfa.getMatches(), request));
        record.setAuditType(ContentAuditRecord.AUDIT_MACHINE);
        record.setDecision(decisionOf(decision));
        record.setReason(truncate(reason, 500));
        record.setHitWords(hitWords.isEmpty() ? null : truncate(toJsonArray(hitWords), 1000));
        record.setRiskLevel(risk);
        record.setAuditSource(truncate(source, 32));
        record.setWordVersion(dfa.getWordVersion());
        record.setModelVersion(MODEL_VERSION);
        record.setCostMs((int) Math.min(Integer.MAX_VALUE, costMs));
        record.setIncomplete(incomplete ? 1 : 0);
        record.setCreateTime(LocalDateTime.now());
        try {
            recordMapper.insert(record);
            result.setRecordId(record.getId());
        } catch (Exception e) {
            log.warn("机审记录落库失败 contentType={} contentId={} cause={}",
                    request.getContentType(), request.getContentId(), e.toString());
        }

        try {
            redisOps.incrementBy(RedisKeys.auditCost(LocalDate.now().toString()),
                    Math.max(1, costMs), Duration.ofDays(2));
        } catch (Exception ignored) {
            // 成本统计失败不影响审核
        }
        return result;
    }

    /**
     * 内容落库后补录 ID；REVIEW 时创建人工任务（同一目标去重）。
     *
     * @return 新建或已存在的任务 ID，PASS/REJECT 返回 null
     */
    public Long attachContent(AuditResult result, AuditRequest request, Long contentId) {
        if (result == null) {
            return null;
        }
        if (result.getRecordId() != null && contentId != null) {
            ContentAuditRecord update = new ContentAuditRecord();
            update.setId(result.getRecordId());
            update.setContentId(contentId);
            try {
                recordMapper.updateById(update);
            } catch (Exception e) {
                log.debug("机审记录补录 contentId 失败 recordId={}", result.getRecordId());
            }
        }
        if (!result.isReview() || contentId == null) {
            return null;
        }
        Long existing = findOpenTask(request.getContentType(), contentId);
        if (existing != null) {
            result.setTaskId(existing);
            return existing;
        }
        AuditTask task = new AuditTask();
        task.setTaskType(defaultString(request.getContentType(), "TEXT"));
        task.setScene(defaultString(request.getScene(), request.getContentType()));
        task.setTargetType(defaultString(request.getContentType(), "TEXT"));
        task.setTargetId(String.valueOf(contentId));
        task.setPriority(result.getRiskLevel() >= 70
                ? AuditTask.PRIORITY_HIGH : AuditTask.PRIORITY_NORMAL);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSubmitterId(request.getUserId());
        task.setTitle(titleOf(request, contentId));
        task.setContentSnapshot(snapshot(request.getText(), null, request));
        task.setMachineResult(truncate(result.getReason(), 500));
        task.setMachineScore(java.math.BigDecimal.valueOf(result.getRiskLevel())
                .divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
        LocalDateTime now = LocalDateTime.now();
        task.setSlaDeadline(now.plusHours(task.getPriority() == AuditTask.PRIORITY_HIGH ? 2 : 24));
        task.setEscalated(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setVersion(0);
        try {
            taskMapper.insert(task);
            result.setTaskId(task.getId());
            return task.getId();
        } catch (Exception e) {
            log.warn("人工审核任务创建失败 contentType={} contentId={} cause={}",
                    request.getContentType(), contentId, e.toString());
            redisOps.addToSet(RedisKeys.auditDlq(),
                    request.getContentType() + ":" + contentId, Duration.ofDays(7));
            return null;
        }
    }

    /** 图片级判断：命中 L3 已拒绝时直接沿用；其余无 OCR 能力时保守转人工。 */
    private ImageVerdict auditImages(List<String> images, String currentDecision, String currentReason) {
        ImageVerdict verdict = new ImageVerdict();
        verdict.decision = AuditResult.PASS;
        if (images == null || images.isEmpty()) {
            verdict.decision = AuditResult.REVIEW;
            verdict.incomplete = true;
            verdict.reason = "图片缺少 OCR/第三方审核能力，已转人工";
            return verdict;
        }
        boolean allCached = true;
        for (String url : images) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            String md5 = DigestUtils.md5DigestAsHex(url.getBytes(StandardCharsets.UTF_8));
            String cached = redisOps.get(RedisKeys.auditImageMd5(md5)).orElse(null);
            if (cached == null) {
                allCached = false;
                continue;
            }
            if ("REJECT".equals(cached)) {
                verdict.decision = AuditResult.REJECT;
                verdict.reason = "图片命中违规缓存结果";
                verdict.incomplete = false;
                return verdict;
            }
            if ("REVIEW".equals(cached) && !AuditResult.REJECT.equals(currentDecision)) {
                verdict.decision = AuditResult.REVIEW;
                verdict.reason = appendReason(verdict.reason, "图片需人工复核");
            }
        }
        if (AuditResult.REJECT.equals(currentDecision)) {
            verdict.decision = AuditResult.REJECT;
            verdict.reason = currentReason;
            verdict.incomplete = false;
            return verdict;
        }
        if (!allCached) {
            verdict.decision = AuditResult.REVIEW;
            verdict.incomplete = true;
            verdict.reason = appendReason(verdict.reason,
                    "图片缺少 OCR/第三方审核能力，已转人工");
        }
        return verdict;
    }

    /** 新用户 7 天内降 10 分阈值；90 天违规 ≥3 次降 20 分。 */
    private int strictness(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            User user = userMapper.selectById(userId);
            if (user != null && user.getCreateTime() != null
                    && user.getCreateTime().isAfter(LocalDateTime.now().minusDays(7))) {
                return 10;
            }
            UserCredit credit = creditMapper.selectById(userId);
            if (credit != null && credit.getViolationCount90d() != null
                    && credit.getViolationCount90d() >= 3) {
                return 20;
            }
        } catch (Exception e) {
            log.debug("读取用户风险画像失败 userId={} cause={}", userId, e.toString());
        }
        return 0;
    }

    private Long findOpenTask(String contentType, Long contentId) {
        try {
            AuditTask task = taskMapper.selectOne(new QueryWrapper<AuditTask>()
                    .eq("target_type", defaultString(contentType, "TEXT"))
                    .eq("target_id", String.valueOf(contentId))
                    .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                    .orderByDesc("id").last("LIMIT 1"));
            return task == null ? null : task.getId();
        } catch (Exception e) {
            return null;
        }
    }

    private static String snapshot(String text, List<SensitiveWordEngine.Match> matches,
                                   AuditRequest request) {
        String masked = text == null ? "" : text;
        if (matches != null && !matches.isEmpty()) {
            List<SensitiveWordEngine.Match> ordered = new ArrayList<>(matches);
            ordered.sort((a, b) -> Integer.compare(b.getStart(), a.getStart()));
            StringBuilder sb = new StringBuilder(masked);
            for (SensitiveWordEngine.Match match : ordered) {
                if (match.getStart() >= 0 && match.getEnd() <= sb.length()
                        && match.getStart() < match.getEnd()) {
                    sb.replace(match.getStart(), match.getEnd(), match.getWord());
                }
            }
            masked = sb.toString();
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            masked = masked + " [图片x" + request.getImages().size() + "]";
        }
        return truncate(masked, SNAPSHOT_LIMIT);
    }

    private static String titleOf(AuditRequest request, Long contentId) {
        if (StringUtils.hasText(request.getTitle())) {
            return truncate(request.getTitle(), 200);
        }
        String type = defaultString(request.getContentType(), "内容");
        String text = request.getText() == null ? "" : request.getText().trim();
        if (StringUtils.hasText(text)) {
            return type + " #" + contentId + "：" + truncate(text, 80);
        }
        return type + " #" + contentId;
    }

    private static int decisionOf(String decision) {
        if (AuditResult.REJECT.equals(decision)) {
            return ContentAuditRecord.DECISION_REJECT;
        }
        if (AuditResult.REVIEW.equals(decision)) {
            return ContentAuditRecord.DECISION_REVIEW;
        }
        return ContentAuditRecord.DECISION_PASS;
    }

    private static String toJsonArray(Set<String> words) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String word : words) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(word.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String appendReason(String current, String extra) {
        if (!StringUtils.hasText(extra)) {
            return current;
        }
        return StringUtils.hasText(current) ? current + "；" + extra : extra;
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class ImageVerdict {
        private String decision;
        private String reason;
        private boolean incomplete;
    }
}
