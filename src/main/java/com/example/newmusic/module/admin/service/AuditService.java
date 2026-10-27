package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.dto.AuditHandleReq;
import com.example.newmusic.module.admin.dto.AuditTaskQueryReq;
import com.example.newmusic.module.admin.entity.AuditRecord;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditRecordMapper;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核工作台：领取（10 分钟锁）→ 处理 → 留痕。
 *
 * <p>领取和处理都使用**条件 UPDATE**，并发下只有一个管理员能成功；
 * 处理超时未完成的任务由定时任务放回待领池（EX-18-011-5），超过 24 小时的任务被标记升级。
 *
 * <p>业务联动目前只覆盖歌曲（调用 {@link SongService#audit}）。
 * 其它类型（评论/歌单等模块尚未交付）会记录审核结论并置 {@code action_applied=0}，
 * 不假装已经改动了不存在的业务表。
 */
@Slf4j
@Service
public class AuditService {

    private final AuditTaskMapper taskMapper;
    private final AuditRecordMapper recordMapper;
    private final SongService songService;
    private final NotificationService notificationService;

    public AuditService(AuditTaskMapper taskMapper, AuditRecordMapper recordMapper,
                        SongService songService, NotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.recordMapper = recordMapper;
        this.songService = songService;
        this.notificationService = notificationService;
    }

    public PageResult<AuditTask> page(AuditTaskQueryReq query) {
        query.normalize();
        QueryWrapper<AuditTask> wrapper = new QueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getPriority() != null) {
            wrapper.eq("priority", query.getPriority());
        }
        if (StringUtils.hasText(query.getTaskType())) {
            wrapper.eq("task_type", query.getTaskType());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq("target_type", query.getTargetType());
        }
        if (Boolean.TRUE.equals(query.getEscalated())) {
            wrapper.eq("escalated", 1);
        }
        if (Boolean.TRUE.equals(query.getOnlyMine())) {
            wrapper.eq("claimed_by", UserContext.requireUserId()).eq("status", AuditTask.STATUS_CLAIMED);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("title", query.getKeyword())
                    .or().like("target_id", query.getKeyword()));
        }
        wrapper.orderByAsc("status").orderByAsc("priority").orderByAsc("create_time");
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
        return recordMapper.selectList(new QueryWrapper<AuditRecord>()
                .eq("task_id", taskId).orderByDesc("create_time"));
    }

    /** 领取任务。已领/已处理均返回 80030，不泄露领取人。 */
    @Transactional
    public AuditTask claim(Long taskId) {
        Long adminId = UserContext.requireUserId();
        if (taskMapper.claim(taskId, adminId) == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        AuditTask task = detail(taskId);
        writeRecord(taskId, adminId, "CLAIM", AuditTask.STATUS_PENDING,
                AuditTask.STATUS_CLAIMED, null);
        return task;
    }

    @Transactional
    public void release(Long taskId) {
        Long adminId = UserContext.requireUserId();
        if (taskMapper.release(taskId, adminId) == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        writeRecord(taskId, adminId, "RELEASE", AuditTask.STATUS_CLAIMED,
                AuditTask.STATUS_PENDING, null);
    }

    @Transactional
    public AuditTask handle(Long taskId, AuditHandleReq req) {
        Long adminId = UserContext.requireUserId();
        AuditTask task = detail(taskId);
        boolean approve = "APPROVE".equalsIgnoreCase(req.getResult());
        if (!approve && (!StringUtils.hasText(req.getReason()) || req.getReason().trim().length() < 5)) {
            throw new BizException(ResultCode.AUDIT_REASON_REQUIRED);
        }
        int afterStatus = approve ? AuditTask.STATUS_APPROVED : AuditTask.STATUS_REJECTED;
        int beforeStatus = task.getStatus();

        boolean actionApplied = applyBusinessAction(task, approve);
        int updated = taskMapper.handle(taskId, adminId, afterStatus, req.getResult().toUpperCase(),
                StringUtils.hasText(req.getReason()) ? req.getReason().trim() : null,
                actionApplied ? 1 : 0);
        if (updated == 0) {
            throw new BizException(ResultCode.AUDIT_TASK_CONFLICT);
        }
        writeRecord(taskId, adminId, req.getResult().toUpperCase(), beforeStatus, afterStatus,
                req.getReason());
        notifySubmitter(task, approve, req.getReason());
        log.info("审核任务处理完成 taskId={} result={} adminId={} actionApplied={}",
                taskId, req.getResult(), adminId, actionApplied);
        return detail(taskId);
    }

    /**
     * 业务联动。返回 true 表示确实改动了业务数据。
     * 目前只有歌曲审核是真实可执行的。
     */
    private boolean applyBusinessAction(AuditTask task, boolean approve) {
        if (!"SONG".equalsIgnoreCase(task.getTaskType())) {
            return false;
        }
        Long songId = parseId(task.getTargetId());
        songService.audit(songId, approve ? 1 : 2);
        return true;
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "任务目标 ID 非法：" + value);
        }
    }

    private void writeRecord(Long taskId, Long adminId, String action,
                             Integer beforeStatus, Integer afterStatus, String reason) {
        LoginUser admin = UserContext.get();
        AuditRecord record = new AuditRecord();
        record.setTaskId(taskId);
        record.setAuditorId(adminId);
        record.setAuditorName(admin != null
                ? (StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername())
                : null);
        record.setAction(action);
        record.setBeforeStatus(beforeStatus);
        record.setAfterStatus(afterStatus);
        record.setReason(reason);
        record.setCreateTime(LocalDateTime.now());
        recordMapper.insert(record);
    }

    private void notifySubmitter(AuditTask task, boolean approve, String reason) {
        if (task.getSubmitterId() == null) {
            return;
        }
        try {
            Notification n = new Notification();
            n.setUserId(task.getSubmitterId());
            n.setNotifyType(Notification.TYPE_AUDIT);
            n.setTitle(approve ? "内容审核通过" : "内容审核未通过");
            n.setContent(approve
                    ? "您提交的「" + safeTitle(task) + "」已通过审核。"
                    : "您提交的「" + safeTitle(task) + "」未通过审核：" + reason);
            n.setIsRead(0);
            notificationService.createAndPush(n);
        } catch (Exception e) {
            log.warn("审核结果通知失败 taskId={} cause={}", task.getId(), e.toString());
        }
    }

    private String safeTitle(AuditTask task) {
        return StringUtils.hasText(task.getTitle()) ? task.getTitle() : "内容 #" + task.getTargetId();
    }

    // ------------------------------------------------------------------ 定时任务入口

    public int releaseExpiredClaims() {
        int released = taskMapper.releaseExpired();
        if (released > 0) {
            log.info("放回超时领取的审核任务 {} 个", released);
        }
        return released;
    }

    public int escalateOverdueTasks() {
        int count = taskMapper.escalateOverdue();
        if (count > 0) {
            log.warn("升级超过 24 小时未处理的审核任务 {} 个", count);
        }
        return count;
    }
}
