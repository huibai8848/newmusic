package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.dto.BanReq;
import com.example.newmusic.module.admin.dto.ReportHandleReq;
import com.example.newmusic.module.admin.dto.ReportQueryReq;
import com.example.newmusic.module.admin.entity.Report;
import com.example.newmusic.module.admin.mapper.ReportMapper;
import com.example.newmusic.module.contentsafety.dto.RestrictReq;
import com.example.newmusic.module.contentsafety.service.ContentDispositionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 举报处理。
 *
 * <p>一次处理 = 条件更新举报状态 + 可选业务处置 + 合并同目标举报 + 通知举报人。
 * 前四步在同一事务里；通知放在最后，失败只 WARN（举报结果已经落库，不能因为
 * 通知服务抖动而把整次处置回滚）。
 *
 * <p>举报成立率为审核员质量指标（BR-18-106）：所有结果都带 handled_by / handle_result，
 * 可以按人聚合。
 */
@Slf4j
@Service
public class ReportService {

    private final ReportMapper reportMapper;
    private final SongService songService;
    private final AdminUserManageService userManageService;
    private final AdminRoomService roomService;
    private final NotificationService notificationService;
    private final ContentDispositionService dispositionService;
    private final UserRestrictionService restrictionService;

    public ReportService(ReportMapper reportMapper, SongService songService,
                         AdminUserManageService userManageService,
                         AdminRoomService roomService,
                         NotificationService notificationService,
                         ContentDispositionService dispositionService,
                         UserRestrictionService restrictionService) {
        this.reportMapper = reportMapper;
        this.songService = songService;
        this.userManageService = userManageService;
        this.roomService = roomService;
        this.notificationService = notificationService;
        this.dispositionService = dispositionService;
        this.restrictionService = restrictionService;
    }

    public PageResult<Report> page(ReportQueryReq query) {
        query.normalize();
        QueryWrapper<Report> wrapper = new QueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getPriority() != null) {
            wrapper.eq("priority", query.getPriority());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq("target_type", query.getTargetType());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("target_name", query.getKeyword())
                    .or().like("reporter_name", query.getKeyword())
                    .or().like("reason", query.getKeyword()));
        }
        wrapper.orderByAsc("status").orderByAsc("priority").orderByDesc("create_time");
        return PageResult.of(reportMapper.selectPage(query.toPage(), wrapper), query.isTruncated());
    }

    public Report detail(Long id) {
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "举报不存在");
        }
        return report;
    }

    @Transactional
    public Report handle(Long reportId, ReportHandleReq req) {
        Long adminId = UserContext.requireUserId();
        Report report = detail(reportId);
        if (report.getStatus() != null
                && report.getStatus() != Report.STATUS_PENDING
                && report.getStatus() != Report.STATUS_NEED_MORE) {
            throw new BizException(ResultCode.REPORT_ALREADY_HANDLED);
        }
        int targetStatus = statusOf(req.getResult());
        String action = StringUtils.hasText(req.getAction()) ? req.getAction().toUpperCase() : "NONE";

        int updated = reportMapper.handle(reportId, adminId, targetStatus,
                req.getResult().toUpperCase(), req.getReason(), action);
        if (updated == 0) {
            throw new BizException(ResultCode.REPORT_ALREADY_HANDLED);
        }
        applyAction(report, action, req.getReason(), adminId);
        if (Boolean.TRUE.equals(req.getMergeSiblings())) {
            int merged = reportMapper.mergeSiblings(reportId, report.getTargetType(), report.getTargetId());
            log.info("举报合并 reportId={} merged={}", reportId, merged);
        }
        notifyReporter(report, req);
        log.info("举报处理完成 reportId={} result={} action={} adminId={}",
                reportId, req.getResult(), action, adminId);
        return detail(reportId);
    }

    private int statusOf(String result) {
        switch (result.toUpperCase()) {
            case "VALID":
                return Report.STATUS_VALID;
            case "INVALID":
                return Report.STATUS_INVALID;
            case "NEED_MORE":
                return Report.STATUS_NEED_MORE;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "未知的举报处理结论");
        }
    }

    /**
     * 联动处置。任何一步失败都会让整个事务回滚，举报保持原状态，避免「标记已处理但内容还在」。
     */
    private void applyAction(Report report, String action, String reason, Long adminId) {
        if ("NONE".equals(action)) {
            return;
        }
        Long targetId = parseId(report.getTargetId());
        switch (action) {
            case "OFFLINE_SONG":
                songService.changeStatus(targetId, 0);
                break;
            case "BAN_USER": {
                BanReq ban = new BanReq();
                ban.setReason(reason);
                ban.setBanUntil(LocalDateTime.now().plusDays(7));
                userManageService.ban(Collections.singletonList(targetId), ban, adminId);
                break;
            }
            case "CLOSE_ROOM":
                roomService.forceClose(targetId, reason, adminId);
                break;
            case "HIDE_CONTENT":
            case "DELETE_CONTENT":
                dispositionService.hide(report.getTargetType(), targetId, adminId);
                break;
            case "RESTRICT_USER":
            case "MUTE_USER":
            case "BAN_TARGET_USER":
                restrictTarget(report, reason, adminId, action);
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的处置动作：" + action);
        }
    }

    /** 举报处置升级到账号维度：优先用被举报内容的作者，USER 类型直接用目标 ID。 */
    private void restrictTarget(Report report, String reason, Long adminId, String action) {
        Long userId = report.getTargetUserId();
        if (userId == null && "USER".equalsIgnoreCase(report.getTargetType())) {
            userId = parseId(report.getTargetId());
        }
        if (userId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "该举报缺少可处置的用户");
        }
        RestrictReq req = new RestrictReq();
        req.setReason(reason);
        req.setRelatedContentType(report.getTargetType());
        req.setRelatedContentId(parseIdOrNull(report.getTargetId()));
        req.setRelatedReportId(report.getId());
        if ("MUTE_USER".equals(action)) {
            req.setLevel(3);
            req.setType(2);
            req.setDays(7);
        } else if ("BAN_TARGET_USER".equals(action)) {
            req.setLevel(4);
            req.setType(3);
            req.setDays(7);
        } else {
            req.setLevel(2);
            req.setType(1);
            req.setDays(7);
        }
        restrictionService.restrict(userId, req);
    }

    private Long parseIdOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "举报目标 ID 非法：" + value);
        }
    }

    private void notifyReporter(Report report, ReportHandleReq req) {
        String content = resultContent(report, req);
        notifyOne(report.getReporterId(), content, report.getId());
        // 模块 10 D-10：社交端提交时已合并到本条主举报的举报人，也要收到同一结果
        try {
            for (Report merged : reportMapper.selectMerged(report.getId())) {
                notifyOne(merged.getReporterId(), content, merged.getId());
            }
        } catch (Exception e) {
            log.warn("合并举报结果通知失败 reportId={} cause={}", report.getId(), e.toString());
        }
    }

    private String resultContent(Report report, ReportHandleReq req) {
        String target = StringUtils.hasText(report.getTargetName())
                ? report.getTargetName() : ("#" + report.getTargetId());
        if ("VALID".equalsIgnoreCase(req.getResult())) {
            return "您对「" + target + "」的举报已成立，我们已处理：" + req.getReason();
        }
        if ("INVALID".equalsIgnoreCase(req.getResult())) {
            return "您对「" + target + "」的举报经核查未发现问题：" + req.getReason();
        }
        return "您对「" + target + "」的举报需要补充材料：" + req.getReason();
    }

    private void notifyOne(Long userId, String content, Long reportId) {
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setNotifyType(Notification.TYPE_REPORT);
            n.setTitle("举报处理结果");
            n.setContent(content);
            n.setIsRead(0);
            notificationService.createAndPush(n);
        } catch (Exception e) {
            log.warn("举报结果通知失败 reportId={} userId={} cause={}", reportId, userId, e.toString());
        }
    }
}
