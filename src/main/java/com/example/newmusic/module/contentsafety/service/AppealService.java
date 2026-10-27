package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.contentsafety.dto.AppealCreateReq;
import com.example.newmusic.module.contentsafety.dto.AppealHandleReq;
import com.example.newmusic.module.contentsafety.dto.AppealQueryReq;
import com.example.newmusic.module.contentsafety.entity.ContentAppeal;
import com.example.newmusic.module.contentsafety.entity.ContentAuditRecord;
import com.example.newmusic.module.contentsafety.entity.UserRestriction;
import com.example.newmusic.module.contentsafety.mapper.ContentAppealMapper;
import com.example.newmusic.module.contentsafety.mapper.ContentAuditRecordMapper;
import com.example.newmusic.module.contentsafety.mapper.UserRestrictionMapper;
import com.example.newmusic.module.contentsafety.support.SensitiveWordEngine;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 申诉（FR-27-005）。
 *
 * <p>三条硬约束在提交入口一次校验：同一处置只能申诉 1 次、处置后 7 天内、每日 3 次。
 * 处理时原审核员回避；通过后恢复内容/解除处置并补回信用分，同时留一条
 * {@code AUDIT_APPEAL} 审核记录用于误判率统计。
 */
@Slf4j
@Service
public class AppealService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContentAppealMapper appealMapper;
    private final ContentAuditRecordMapper contentRecordMapper;
    private final UserRestrictionMapper restrictionMapper;
    private final AuditTaskMapper taskMapper;
    private final SensitiveWordEngine engine;
    private final ContentDispositionService dispositionService;
    private final UserRestrictionService restrictionService;
    private final NotificationService notificationService;
    private final RedisOps redisOps;

    public AppealService(ContentAppealMapper appealMapper,
                         ContentAuditRecordMapper contentRecordMapper,
                         UserRestrictionMapper restrictionMapper, AuditTaskMapper taskMapper,
                         SensitiveWordEngine engine, ContentDispositionService dispositionService,
                         UserRestrictionService restrictionService,
                         NotificationService notificationService, RedisOps redisOps) {
        this.appealMapper = appealMapper;
        this.contentRecordMapper = contentRecordMapper;
        this.restrictionMapper = restrictionMapper;
        this.taskMapper = taskMapper;
        this.engine = engine;
        this.dispositionService = dispositionService;
        this.restrictionService = restrictionService;
        this.notificationService = notificationService;
        this.redisOps = redisOps;
    }

    @Transactional
    public ContentAppeal create(AppealCreateReq req) {
        Long userId = UserContext.requireUserId();
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (engine.detect(reason).getMaxLevel() > 0) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL, "申诉理由包含违规信息，请修改后再提交");
        }
        String targetType = req.getTargetType() == null ? "" : req.getTargetType().trim().toUpperCase();
        if (!ContentAppeal.TARGET_CONTENT.equals(targetType)
                && !ContentAppeal.TARGET_ACCOUNT.equals(targetType)) {
            throw new BizException(ResultCode.PARAM_INVALID, "申诉对象只能为 CONTENT/ACCOUNT");
        }
        Long targetId;
        try {
            targetId = Long.valueOf(req.getTargetId());
        } catch (Exception e) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND);
        }
        long duplicate = appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .eq("user_id", userId).eq("target_type", targetType)
                .eq("target_id", req.getTargetId()));
        if (duplicate > 0) {
            throw new BizException(ResultCode.APPEAL_DUPLICATED);
        }
        long daily = redisOps.increment(RedisKeys.auditAppealDay(userId, LocalDate.now().toString()),
                Duration.ofDays(1));
        if (daily > 3) {
            throw new BizException(ResultCode.APPEAL_DAILY_LIMIT);
        }

        LocalDateTime now = LocalDateTime.now();
        Long originalOperator = null;
        if (ContentAppeal.TARGET_CONTENT.equals(targetType)) {
            ContentAuditRecord record = contentRecordMapper.selectById(targetId);
            if (record == null || !userId.equals(record.getUserId())
                    || !Integer.valueOf(ContentAuditRecord.DECISION_REJECT).equals(record.getDecision())) {
                throw new BizException(ResultCode.APPEAL_NOT_FOUND);
            }
            if (record.getCreateTime() != null && record.getCreateTime().isBefore(now.minusDays(7))) {
                throw new BizException(ResultCode.APPEAL_WINDOW_EXPIRED);
            }
            if (record.getContentId() == null) {
                throw new BizException(ResultCode.APPEAL_NOT_FOUND, "原内容不存在，无法申诉");
            }
            originalOperator = record.getOperatorId();
        } else {
            UserRestriction restriction = restrictionMapper.selectById(targetId);
            if (restriction == null || !userId.equals(restriction.getUserId())) {
                throw new BizException(ResultCode.APPEAL_NOT_FOUND);
            }
            if (restriction.getCreateTime() != null
                    && restriction.getCreateTime().isBefore(now.minusDays(7))) {
                throw new BizException(ResultCode.APPEAL_WINDOW_EXPIRED);
            }
            originalOperator = restriction.getOperatorId();
        }

        ContentAppeal appeal = new ContentAppeal();
        appeal.setUserId(userId);
        appeal.setTargetType(targetType);
        appeal.setTargetId(req.getTargetId());
        appeal.setReason(reason);
        appeal.setEvidenceImages(toJson(req.getEvidenceImages()));
        appeal.setStatus(ContentAppeal.STATUS_PENDING);
        appeal.setOriginalOperatorId(originalOperator);
        appeal.setSlaDeadline(now.plusHours(24));
        appeal.setCreateTime(now);
        appeal.setUpdateTime(now);
        appealMapper.insert(appeal);
        createAppealTask(appeal);
        log.info("申诉提交 appealId={} userId={} targetType={} targetId={}",
                appeal.getId(), userId, targetType, targetId);
        return appeal;
    }

    public PageResult<ContentAppeal> my(int pageNum, int pageSize) {
        Long userId = UserContext.requireUserId();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ContentAppeal> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        return PageResult.of(appealMapper.selectPage(page, new QueryWrapper<ContentAppeal>()
                .eq("user_id", userId).orderByDesc("create_time").orderByDesc("id")));
    }

    public PageResult<ContentAppeal> page(AppealQueryReq query) {
        query.normalize();
        QueryWrapper<ContentAppeal> wrapper = new QueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq("target_type", query.getTargetType().trim().toUpperCase());
        }
        if (Boolean.TRUE.equals(query.getOverdue())) {
            wrapper.eq("status", ContentAppeal.STATUS_PENDING)
                    .isNotNull("sla_deadline").lt("sla_deadline", LocalDateTime.now());
        }
        wrapper.orderByAsc("status").orderByAsc("sla_deadline").orderByDesc("create_time");
        return PageResult.of(appealMapper.selectPage(query.toPage(), wrapper), query.isTruncated());
    }

    public ContentAppeal detail(Long id) {
        ContentAppeal appeal = appealMapper.selectById(id);
        if (appeal == null) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND);
        }
        return appeal;
    }

    /** C 端详情：不是本人申诉一律按不存在处理，避免探测他人处置记录。 */
    public ContentAppeal myDetail(Long id) {
        ContentAppeal appeal = detail(id);
        if (!appeal.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND);
        }
        return appeal;
    }

    @Transactional
    public ContentAppeal handle(Long appealId, AppealHandleReq req) {
        Long adminId = UserContext.requireUserId();
        ContentAppeal appeal = detail(appealId);
        if (!Integer.valueOf(ContentAppeal.STATUS_PENDING).equals(appeal.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "该申诉已处理");
        }
        if (appeal.getOriginalOperatorId() != null && appeal.getOriginalOperatorId().equals(adminId)) {
            throw new BizException(ResultCode.APPEAL_SELF_REVIEW);
        }
        boolean approve = "APPROVE".equalsIgnoreCase(req.getDecision());
        if (!approve && !"REJECT".equalsIgnoreCase(req.getDecision())) {
            throw new BizException(ResultCode.PARAM_INVALID, "处理结论只能为 APPROVE/REJECT");
        }
        LocalDateTime now = LocalDateTime.now();
        Long targetId = parseId(appeal.getTargetId());
        if (approve) {
            if (ContentAppeal.TARGET_CONTENT.equals(appeal.getTargetType())) {
                approveContent(appeal, targetId, adminId, Boolean.FALSE.equals(req.getRestoreCredit()) ? false : true);
            } else {
                approveAccount(appeal, targetId, adminId, Boolean.FALSE.equals(req.getRestoreCredit()) ? false : true);
            }
        }
        ContentAppeal update = new ContentAppeal();
        update.setId(appealId);
        update.setStatus(approve ? ContentAppeal.STATUS_APPROVED : ContentAppeal.STATUS_REJECTED);
        update.setResult(req.getResult());
        update.setReply(req.getResult());
        update.setHandledBy(adminId);
        update.setHandledAt(now);
        update.setUpdateTime(now);
        appealMapper.updateById(update);

        closeTask(appealId, adminId, approve, req.getResult());
        notifyUser(appeal.getUserId(), approve, req.getResult());
        log.info("申诉处理 appealId={} decision={} adminId={}",
                appealId, req.getDecision(), adminId);
        return detail(appealId);
    }

    public Map<String, Object> stats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 90)));
        long total = appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .ge("create_time", since));
        long approved = appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .eq("status", ContentAppeal.STATUS_APPROVED).ge("create_time", since));
        long rejected = appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .eq("status", ContentAppeal.STATUS_REJECTED).ge("create_time", since));
        long pending = appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .eq("status", ContentAppeal.STATUS_PENDING));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("approved", approved);
        out.put("rejected", rejected);
        out.put("pending", pending);
        out.put("misjudgeRate", approved + rejected == 0 ? 0
                : Math.round(approved * 10000.0 / (approved + rejected)) / 100.0);
        out.put("overdue", appealMapper.selectCount(new QueryWrapper<ContentAppeal>()
                .eq("status", ContentAppeal.STATUS_PENDING)
                .isNotNull("sla_deadline").lt("sla_deadline", LocalDateTime.now())));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void approveContent(ContentAppeal appeal, Long recordId, Long adminId, boolean restoreCredit) {
        ContentAuditRecord record = contentRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND, "原审核记录不存在");
        }
        if (record.getContentId() != null && StringUtils.hasText(record.getContentType())) {
            try {
                dispositionService.restore(record.getContentType(), record.getContentId(), adminId);
            } catch (Exception e) {
                // EX-27-005-5：原内容已不存在时保留申诉通过，但把情况写进结果。
                log.warn("申诉恢复内容失败，按原内容缺失处理 recordId={} cause={}",
                        recordId, e.toString());
            }
        }
        if (restoreCredit && record.getUserId() != null) {
            restrictionService.restoreCredit(record.getUserId(), 3,
                    "申诉成功，撤销内容处置扣分", appeal.getId());
        }
        ContentAuditRecord appealRecord = new ContentAuditRecord();
        appealRecord.setContentType(record.getContentType());
        appealRecord.setContentId(record.getContentId());
        appealRecord.setUserId(record.getUserId());
        appealRecord.setScene(record.getScene());
        appealRecord.setContentSnapshot(record.getContentSnapshot());
        appealRecord.setAuditType(ContentAuditRecord.AUDIT_APPEAL);
        appealRecord.setDecision(ContentAuditRecord.DECISION_PASS);
        appealRecord.setReason("申诉成立（原审核记录 #" + recordId + "）");
        appealRecord.setAuditSource("APPEAL");
        appealRecord.setOperatorId(adminId);
        appealRecord.setCreateTime(LocalDateTime.now());
        contentRecordMapper.insert(appealRecord);
    }

    private void approveAccount(ContentAppeal appeal, Long restrictionId, Long adminId,
                                boolean restoreCredit) {
        UserRestriction restriction = restrictionMapper.selectById(restrictionId);
        if (restriction == null) {
            throw new BizException(ResultCode.RESTRICTION_NOT_FOUND);
        }
        restrictionService.releaseForAppeal(restriction);
        if (restoreCredit) {
            int points = UserRestrictionService.creditPointsForLevel(
                    restriction.getLevel(), restriction.getEndTime() == null ? null
                            : (int) java.time.Duration.between(restriction.getStartTime(),
                            restriction.getEndTime()).toDays());
            restrictionService.restoreCredit(restriction.getUserId(), points,
                    "申诉成功，撤销处置扣分", appeal.getId());
        }
    }

    private void createAppealTask(ContentAppeal appeal) {
        try {
            AuditTask task = new AuditTask();
            task.setTaskType("APPEAL");
            task.setScene("APPEAL");
            task.setTargetType("APPEAL");
            task.setTargetId(String.valueOf(appeal.getId()));
            task.setPriority(AuditTask.PRIORITY_HIGH);
            task.setStatus(AuditTask.STATUS_PENDING);
            task.setSubmitterId(appeal.getUserId());
            task.setTitle("申诉 #" + appeal.getId());
            task.setContentSnapshot(truncate(appeal.getReason(), 1000));
            task.setMachineResult("用户申诉，需独立复核");
            task.setSlaDeadline(appeal.getSlaDeadline());
            task.setEscalated(0);
            task.setCreateTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            task.setVersion(0);
            taskMapper.insert(task);
        } catch (Exception e) {
            log.warn("申诉审核任务创建失败 appealId={} cause={}", appeal.getId(), e.toString());
        }
    }

    private void closeTask(Long appealId, Long adminId, boolean approve, String result) {
        try {
            AuditTask task = taskMapper.selectOne(new QueryWrapper<AuditTask>()
                    .eq("target_type", "APPEAL").eq("target_id", String.valueOf(appealId))
                    .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                    .orderByDesc("id").last("LIMIT 1"));
            if (task != null) {
                taskMapper.handle(task.getId(), adminId,
                        approve ? AuditTask.STATUS_APPROVED : AuditTask.STATUS_REJECTED,
                        approve ? "APPROVE" : "REJECT", result, 1);
            }
        } catch (Exception e) {
            log.warn("申诉任务关闭失败 appealId={} cause={}", appealId, e.toString());
        }
    }

    private void notifyUser(Long userId, boolean approve, String result) {
        try {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setNotifyType(Notification.TYPE_APPEAL);
            notification.setTitle(approve ? "申诉成功" : "申诉结果通知");
            notification.setContent(approve
                    ? "您的申诉已成立，相关内容/权限已恢复。" + (StringUtils.hasText(result) ? " " + result : "")
                    : "您的申诉未通过：" + result);
            notification.setCreateTime(LocalDateTime.now());
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("申诉结果通知失败 userId={} cause={}", userId, e.toString());
        }
    }

    private String toJson(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(images);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception e) {
            throw new BizException(ResultCode.APPEAL_NOT_FOUND);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
