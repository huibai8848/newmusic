package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.entity.AuditRecord;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditRecordMapper;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.contentsafety.dto.AuditBatchDecisionReq;
import com.example.newmusic.module.contentsafety.dto.AuditDecisionReq;
import com.example.newmusic.module.contentsafety.dto.AuditQueueQuery;
import com.example.newmusic.module.contentsafety.dto.RestrictReq;
import com.example.newmusic.module.contentsafety.entity.ContentAuditRecord;
import com.example.newmusic.module.contentsafety.mapper.ContentAuditRecordMapper;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工审核工作台（FR-27-004）。
 *
 * <p>队列直接复用模块 09 的 {@code t_audit_task} 与 10 分钟领取锁，新增的只是
 * scene / SLA / 内容处置联动。处理时先处置内容、再改任务状态，两步同一事务，
 * 保证不会出现「任务显示已处置、内容还在线」。
 */
@Slf4j
@Service
public class AuditWorkbenchService {

    private static final Set<String> DISPOSABLE = new HashSet<>(Arrays.asList(
            "COMMENT", "POST", "PLAYLIST", "ROOM", "MESSAGE", "PRIVATE_MESSAGE",
            "DANMAKU", "ROOM_MESSAGE", "SONG"));

    private static final Set<String> QUEUE_TYPES = new HashSet<>(Arrays.asList(
            "COMMENT", "POST", "PLAYLIST", "ROOM", "MESSAGE", "DANMAKU", "IMAGE", "APPEAL"));

    private final AuditTaskMapper taskMapper;
    private final AuditRecordMapper auditRecordMapper;
    private final ContentAuditRecordMapper contentRecordMapper;
    private final ContentDispositionService dispositionService;
    private final UserRestrictionService restrictionService;
    private final NotificationService notificationService;

    public AuditWorkbenchService(AuditTaskMapper taskMapper, AuditRecordMapper auditRecordMapper,
                                 ContentAuditRecordMapper contentRecordMapper,
                                 ContentDispositionService dispositionService,
                                 UserRestrictionService restrictionService,
                                 NotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.auditRecordMapper = auditRecordMapper;
        this.contentRecordMapper = contentRecordMapper;
        this.dispositionService = dispositionService;
        this.restrictionService = restrictionService;
        this.notificationService = notificationService;
    }

    public PageResult<AuditTask> queue(AuditQueueQuery query) {
        query.normalize();
        QueryWrapper<AuditTask> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getType())) {
            wrapper.eq("task_type", query.getType().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getScene())) {
            wrapper.eq("scene", query.getScene().trim().toUpperCase());
        }
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        } else {
            wrapper.in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED);
        }
        if (query.getPriority() != null) {
            wrapper.eq("priority", query.getPriority());
        }
        if (query.getClaimedBy() != null) {
            wrapper.eq("claimed_by", query.getClaimedBy());
        }
        if (Boolean.TRUE.equals(query.getOverdue())) {
            wrapper.in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                    .isNotNull("sla_deadline").lt("sla_deadline", LocalDateTime.now());
        }
        wrapper.orderByAsc("priority").orderByAsc("sla_deadline").orderByAsc("create_time");
        return PageResult.of(taskMapper.selectPage(query.toPage(), wrapper), query.isTruncated());
    }

    public AuditTask detail(Long taskId) {
        AuditTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核任务不存在");
        }
        return task;
    }

    public List<AuditRecord> records(Long taskId) {
        return auditRecordMapper.selectList(new QueryWrapper<AuditRecord>()
                .eq("task_id", taskId).orderByDesc("create_time"));
    }

    @Transactional
    public AuditTask claim(Long taskId) {
        Long adminId = UserContext.requireUserId();
        if (taskMapper.claim(taskId, adminId) == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        AuditTask task = detail(taskId);
        writeTrail(taskId, adminId, "CLAIM", AuditTask.STATUS_PENDING,
                AuditTask.STATUS_CLAIMED, null);
        return task;
    }

    @Transactional
    public void release(Long taskId) {
        Long adminId = UserContext.requireUserId();
        if (taskMapper.release(taskId, adminId) == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        writeTrail(taskId, adminId, "RELEASE", AuditTask.STATUS_CLAIMED,
                AuditTask.STATUS_PENDING, null);
    }

    @Transactional
    public AuditTask decide(Long taskId, AuditDecisionReq req) {
        Long adminId = UserContext.requireUserId();
        AuditTask task = detail(taskId);
        if (task.getStatus() == null || (task.getStatus() != AuditTask.STATUS_PENDING
                && task.getStatus() != AuditTask.STATUS_CLAIMED)) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        if (task.getClaimedBy() != null && !task.getClaimedBy().equals(adminId)
                && task.getClaimExpireAt() != null
                && task.getClaimExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        boolean approve = "APPROVE".equalsIgnoreCase(req.getDecision());
        if (!approve && !"REJECT".equalsIgnoreCase(req.getDecision())) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核结论只能为 APPROVE/REJECT");
        }
        String rejectReason = req.getRejectReason() == null ? null : req.getRejectReason().trim();
        if (!approve && (!StringUtils.hasText(rejectReason) || rejectReason.length() < 2)) {
            throw new BizException(ResultCode.AUDIT_REASON_REQUIRED);
        }

        String type = task.getTargetType();
        Long contentId = parseId(task.getTargetId());
        String contentAction = req.getContentAction();
        if (!StringUtils.hasText(contentAction)) {
            contentAction = approve ? "PASS" : "HIDE";
        }
        boolean contentApplied = false;
        if (contentId != null && type != null && DISPOSABLE.contains(type.toUpperCase())) {
            contentApplied = dispositionService.apply(type, contentId, contentAction, adminId);
            if (!approve && "DELETE".equalsIgnoreCase(contentAction)) {
                restrictionService.contentPenalty(task.getSubmitterId(), contentId, type);
            }
        }

        boolean userApplied = false;
        if (StringUtils.hasText(req.getUserAction())
                && !"NONE".equalsIgnoreCase(req.getUserAction())
                && task.getSubmitterId() != null) {
            RestrictReq restrict = toRestrict(task, req);
            restrictionService.restrict(task.getSubmitterId(), restrict);
            userApplied = true;
        }

        int afterStatus = approve ? AuditTask.STATUS_APPROVED : AuditTask.STATUS_REJECTED;
        int beforeStatus = task.getStatus();
        int updated = taskMapper.handle(taskId, adminId, afterStatus,
                approve ? "APPROVE" : "REJECT", rejectReason,
                contentApplied || userApplied ? 1 : 0);
        if (updated == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        writeTrail(taskId, adminId, approve ? "APPROVE" : "REJECT", beforeStatus, afterStatus,
                rejectReason);
        writeManualRecord(task, approve, rejectReason, adminId);
        if (Boolean.TRUE.equals(req.getNotify()) || req.getNotify() == null) {
            notifySubmitter(task, approve, rejectReason);
        }
        log.info("人工审核完成 taskId={} decision={} contentAction={} adminId={}",
                taskId, req.getDecision(), contentAction, adminId);
        return detail(taskId);
    }

    @Transactional
    public Map<String, Object> batchDecide(AuditBatchDecisionReq req) {
        if (!Boolean.TRUE.equals(req.getConfirmed())) {
            throw new BizException(ResultCode.PARAM_INVALID, "批量处置需要二次确认");
        }
        List<Long> ids = req.getTaskIds();
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请选择任务");
        }
        if (ids.size() > 100) {
            throw new BizException(ResultCode.PARAM_INVALID, "单次最多 100 条");
        }
        int success = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            try {
                AuditDecisionReq one = new AuditDecisionReq();
                one.setDecision(req.getDecision());
                one.setRejectReason(req.getRejectReason());
                one.setContentAction(req.getContentAction());
                one.setUserAction(req.getUserAction());
                one.setUserActionDays(req.getUserActionDays());
                one.setNotify(req.getNotify());
                decide(id, one);
                success++;
            } catch (Exception e) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("taskId", String.valueOf(id));
                failure.put("reason", e.getMessage());
                failures.add(failure);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failures.size());
        result.put("failures", failures);
        return result;
    }

    public Map<String, Object> stats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 90)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", since);
        out.put("byDecision", contentRecordMapper.countByDecision(since));
        out.put("bySource", contentRecordMapper.countBySource(since));
        out.put("byContentType", contentRecordMapper.countByContentType(since));
        out.put("machine", countMachine(since));
        out.put("manual", contentRecordMapper.selectCount(new QueryWrapper<ContentAuditRecord>()
                .eq("audit_type", ContentAuditRecord.AUDIT_MANUAL).ge("create_time", since)));
        out.put("queue", queueStats());
        out.put("sla", slaStats());
        return out;
    }

    public Map<String, Object> queueStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("status", AuditTask.STATUS_PENDING)));
        out.put("claimed", taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("status", AuditTask.STATUS_CLAIMED)));
        out.put("approvedToday", taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("status", AuditTask.STATUS_APPROVED)
                .ge("handled_at", LocalDateTime.now().toLocalDate().atStartOfDay())));
        out.put("rejectedToday", taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("status", AuditTask.STATUS_REJECTED)
                .ge("handled_at", LocalDateTime.now().toLocalDate().atStartOfDay())));
        return out;
    }

    public Map<String, Object> slaStats() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        long overdue = taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                .isNotNull("sla_deadline").lt("sla_deadline", now));
        long escalated = taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("escalated", 1)
                .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED));
        out.put("overdue", overdue);
        out.put("escalated", escalated);
        out.put("claimExpiring", taskMapper.selectCount(new QueryWrapper<AuditTask>()
                .eq("status", AuditTask.STATUS_CLAIMED)
                .isNotNull("claim_expire_at")
                .lt("claim_expire_at", now.plusMinutes(2))));
        List<AuditTask> handled = taskMapper.selectList(new QueryWrapper<AuditTask>()
                .in("status", AuditTask.STATUS_APPROVED, AuditTask.STATUS_REJECTED)
                .isNotNull("handled_at").ge("handled_at", now.minusDays(1))
                .last("LIMIT 5000"));
        long totalSeconds = 0;
        int counted = 0;
        for (AuditTask task : handled) {
            if (task.getCreateTime() != null && task.getHandledAt() != null) {
                totalSeconds += Duration.between(task.getCreateTime(), task.getHandledAt()).getSeconds();
                counted++;
            }
        }
        out.put("handled24h", handled.size());
        out.put("avgHandleSeconds", counted == 0 ? 0 : totalSeconds / counted);
        return out;
    }

    public Map<String, Object> dailyReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", LocalDateTime.now().toLocalDate().toString());
        out.put("queue", queueStats());
        out.put("sla", slaStats());
        out.put("last24h", contentRecordMapper.countByDecision(LocalDateTime.now().minusDays(1)));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> countMachine(LocalDateTime since) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", contentRecordMapper.selectCount(new QueryWrapper<ContentAuditRecord>()
                .eq("audit_type", ContentAuditRecord.AUDIT_MACHINE)
                .eq("decision", ContentAuditRecord.DECISION_PASS).ge("create_time", since)));
        out.put("review", contentRecordMapper.selectCount(new QueryWrapper<ContentAuditRecord>()
                .eq("audit_type", ContentAuditRecord.AUDIT_MACHINE)
                .eq("decision", ContentAuditRecord.DECISION_REVIEW).ge("create_time", since)));
        out.put("reject", contentRecordMapper.selectCount(new QueryWrapper<ContentAuditRecord>()
                .eq("audit_type", ContentAuditRecord.AUDIT_MACHINE)
                .eq("decision", ContentAuditRecord.DECISION_REJECT).ge("create_time", since)));
        return out;
    }

    private RestrictReq toRestrict(AuditTask task, AuditDecisionReq req) {
        RestrictReq restrict = new RestrictReq();
        String action = req.getUserAction().trim().toUpperCase();
        switch (action) {
            case "WARN":
                restrict.setLevel(1);
                break;
            case "RESTRICT_COMMENT":
                restrict.setLevel(2);
                restrict.setType(1);
                break;
            case "MUTE":
                restrict.setLevel(3);
                restrict.setType(2);
                break;
            case "BAN":
                restrict.setLevel(4);
                restrict.setType(3);
                break;
            case "RESTRICT_UPLOAD":
                restrict.setLevel(2);
                restrict.setType(5);
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的用户处置：" + action);
        }
        restrict.setReason("审核任务 #" + task.getId() + " 处置");
        restrict.setDays(req.getUserActionDays());
        restrict.setRelatedContentType(task.getTargetType());
        restrict.setRelatedContentId(parseId(task.getTargetId()));
        return restrict;
    }

    private void writeTrail(Long taskId, Long adminId, String action, Integer beforeStatus,
                            Integer afterStatus, String reason) {
        AuditRecord record = new AuditRecord();
        record.setTaskId(taskId);
        record.setAuditorId(adminId);
        LoginUser admin = UserContext.get();
        record.setAuditorName(admin == null ? null
                : (StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername()));
        record.setAction(action);
        record.setBeforeStatus(beforeStatus);
        record.setAfterStatus(afterStatus);
        record.setReason(reason);
        record.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(record);
    }

    private void writeManualRecord(AuditTask task, boolean approve, String reason, Long adminId) {
        ContentAuditRecord record = new ContentAuditRecord();
        record.setContentType(task.getTargetType());
        record.setContentId(parseId(task.getTargetId()));
        record.setUserId(task.getSubmitterId());
        record.setScene(task.getScene());
        record.setContentSnapshot(task.getContentSnapshot());
        record.setAuditType(ContentAuditRecord.AUDIT_MANUAL);
        record.setDecision(approve ? ContentAuditRecord.DECISION_PASS
                : ContentAuditRecord.DECISION_REJECT);
        record.setReason(StringUtils.hasText(reason) ? reason : "人工审核通过");
        record.setRiskLevel(task.getMachineScore() == null ? 0
                : task.getMachineScore().multiply(java.math.BigDecimal.valueOf(100)).intValue());
        record.setAuditSource("MANUAL");
        record.setOperatorId(adminId);
        record.setCreateTime(LocalDateTime.now());
        contentRecordMapper.insert(record);
    }

    private void notifySubmitter(AuditTask task, boolean approve, String reason) {
        if (task.getSubmitterId() == null) {
            return;
        }
        try {
            Notification notification = new Notification();
            notification.setUserId(task.getSubmitterId());
            notification.setNotifyType(Notification.TYPE_AUDIT);
            notification.setTitle(approve ? "内容审核通过" : "内容审核未通过");
            notification.setContent(approve
                    ? "您的内容已通过人工复核。"
                    : "您的内容未通过人工复核：" + reason + "（可在 7 天内提起申诉）");
            notification.setCreateTime(LocalDateTime.now());
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("审核结果通知失败 taskId={} cause={}", task.getId(), e.toString());
        }
    }

    private static Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
