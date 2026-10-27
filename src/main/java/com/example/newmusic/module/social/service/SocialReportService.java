package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.admin.entity.Report;
import com.example.newmusic.module.admin.mapper.ReportMapper;
import com.example.newmusic.module.contentsafety.service.ContentDispositionService;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.dto.ReportCreateReq;
import com.example.newmusic.module.social.entity.Comment;
import com.example.newmusic.module.social.entity.PrivateMessage;
import com.example.newmusic.module.social.mapper.CommentMapper;
import com.example.newmusic.module.social.mapper.PrivateMessageMapper;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.ReportReasonVO;
import com.example.newmusic.module.social.vo.ReportVO;
import com.example.newmusic.module.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 举报提交与「我的举报」（模块 10 FR-19-015）。
 *
 * <p>审核与处置复用模块 09 的 {@code module/admin}；本服务只负责用户侧入口。
 */
@Slf4j
@Service
public class SocialReportService {

    private static final Set<String> HIGH_PRIORITY_REASONS = new HashSet<>(Arrays.asList(
            "POLITICS", "PORNOGRAPHY", "VIOLENCE", "TERRORISM"));

    private static final List<ReportReasonVO> REASONS = Collections.unmodifiableList(Arrays.asList(
            new ReportReasonVO("POLITICS", "涉及政治敏感", "违法违规", 1),
            new ReportReasonVO("PORNOGRAPHY", "色情低俗", "违法违规", 1),
            new ReportReasonVO("VIOLENCE", "暴力血腥", "违法违规", 1),
            new ReportReasonVO("TERRORISM", "恐怖主义", "违法违规", 1),
            new ReportReasonVO("GAMBLING", "赌博诈骗", "违法违规", 1),
            new ReportReasonVO("FRAUD", "诈骗信息", "违法违规", 1),
            new ReportReasonVO("COPYRIGHT", "侵犯版权", "侵权", 2),
            new ReportReasonVO("PRIVACY", "侵犯隐私", "侵权", 1),
            new ReportReasonVO("IMPERSONATION", "冒充他人", "侵权", 2),
            new ReportReasonVO("AD_SPAM", "广告营销", "不良信息", 2),
            new ReportReasonVO("OBSCENE", "辱骂攻击", "不良信息", 2),
            new ReportReasonVO("TROLL", "引战/恶意骚扰", "不良信息", 2),
            new ReportReasonVO("WRONG_INFO", "信息错误", "内容问题", 2),
            new ReportReasonVO("AUDIO_QUALITY", "音质问题", "内容问题", 2),
            new ReportReasonVO("LYRIC_ERROR", "歌词错误", "内容问题", 2),
            new ReportReasonVO("DUPLICATE", "重复内容", "内容问题", 2),
            new ReportReasonVO("OTHER", "其它", "其它", 2)
    ));

    private final ReportMapper reportMapper;
    private final SocialTargets targets;
    private final SocialUserSupport users;
    private final CommentMapper commentMapper;
    private final PrivateMessageMapper privateMessageMapper;
    private final RedisOps redisOps;
    private final SocialProperties properties;
    private final ObjectMapper objectMapper;
    private final ContentDispositionService dispositionService;

    public SocialReportService(ReportMapper reportMapper, SocialTargets targets,
                               SocialUserSupport users, CommentMapper commentMapper,
                               PrivateMessageMapper privateMessageMapper, RedisOps redisOps,
                               SocialProperties properties, ObjectMapper objectMapper,
                               ContentDispositionService dispositionService) {
        this.reportMapper = reportMapper;
        this.targets = targets;
        this.users = users;
        this.commentMapper = commentMapper;
        this.privateMessageMapper = privateMessageMapper;
        this.redisOps = redisOps;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.dispositionService = dispositionService;
    }

    public List<ReportReasonVO> reasons() {
        return REASONS;
    }

    @Transactional
    public ReportVO create(Long userId, ReportCreateReq req) {
        User reporter = users.requireUser(userId);
        String type = targets.requireReportType(req.getTargetType());
        String reasonType = req.getReasonType() == null ? "" : req.getReasonType().trim().toUpperCase();
        if (REASONS.stream().noneMatch(r -> r.getCode().equals(reasonType))) {
            throw new BizException(ResultCode.PARAM_INVALID, "未知的举报理由");
        }

        LocalDateTime now = LocalDateTime.now();
        long daily = redisOps.increment("ml:social:report:day:" + userId + ":" + LocalDate.now(),
                Duration.ofDays(1));
        if (daily > 30) {
            throw new BizException(ResultCode.REPORT_RATE_LIMITED);
        }
        checkMalicious(userId, now);

        Long targetId;
        try {
            targetId = Long.valueOf(req.getTargetId());
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.REPORT_TARGET_NOT_FOUND);
        }
        SocialTargets.TargetInfo info = targets.require(type, targetId, false);
        checkSelfReport(userId, type, targetId);
        checkDedup(userId, type, req.getTargetId(), reasonType, now);

        List<String> evidence = req.getEvidenceUrls();
        if (evidence != null) {
            if (evidence.size() > 6) {
                throw new BizException(ResultCode.REPORT_EVIDENCE_TOO_LARGE);
            }
            for (String url : evidence) {
                if (url != null && url.length() > 2000) {
                    throw new BizException(ResultCode.REPORT_EVIDENCE_TOO_LARGE);
                }
            }
        }

        int priority = priority(type, reasonType);
        String category = REASONS.stream().filter(r -> r.getCode().equals(reasonType))
                .map(ReportReasonVO::getCategory).findFirst().orElse("其它");
        Report report = new Report();
        report.setReportNo(reportNo());
        report.setReporterId(userId);
        report.setReporterName(reporter.getNickname());
        report.setTargetType(type);
        report.setTargetId(req.getTargetId());
        report.setTargetUserId(info.getOwnerId());
        report.setTargetName(info.getName() == null ? null : truncate(info.getName(), 200));
        report.setReasonType(reasonType);
        report.setReasonCategory(category);
        report.setReason(req.getReason() == null ? null : truncate(req.getReason(), 500));
        report.setReasonDetail(req.getReason() == null ? null : truncate(req.getReason(), 500));
        report.setEvidenceUrls(evidence == null || evidence.isEmpty() ? null : toJson(evidence));
        report.setPriority(priority);
        report.setReportCount(1);
        report.setReporterIds("[" + userId + "]");
        report.setSlaDeadline(slaDeadline(priority));
        report.setStatus(Report.STATUS_PENDING);

        // 同一目标+理由已有待处理举报时合并，避免后台出现多个重复任务
        Report main = reportMapper.selectOne(new QueryWrapper<Report>()
                .eq("target_type", type).eq("target_id", req.getTargetId())
                .eq("reason_type", reasonType).eq("status", Report.STATUS_PENDING)
                .orderByAsc("create_time").last("LIMIT 1"));
        if (main != null) {
            report.setStatus(Report.STATUS_MERGED);
            report.setMergedIntoId(main.getId());
        }
        reportMapper.insert(report);
        if (main != null) {
            applyMergeThreshold(main, userId, type, req.getTargetId());
        }
        return toVO(report);
    }

    /**
     * 24 小时合并计数（FR-27-006）：
     * ≥3 提升优先级到高优（SLA 2h），≥10 记录降权信号，≥50 自动隐藏。
     * 自动隐藏失败不回滚举报——举报已经成立，处置可以由人工补做。
     */
    private void applyMergeThreshold(Report main, Long reporterId, String type, String targetId) {
        try {
            reportMapper.mergeInto(main.getId(), reporterId);
            int count = (main.getReportCount() == null ? 1 : main.getReportCount()) + 1;
            if (count >= 3) {
                reportMapper.raisePriority(main.getId(), Report.PRIORITY_HIGH,
                        LocalDateTime.now().plusHours(2));
            }
            if (count >= 50) {
                try {
                    if (!"USER".equals(type)) {
                        dispositionService.hide(type, Long.valueOf(targetId), 0L);
                    }
                    reportMapper.markAutoHidden(main.getId());
                } catch (Exception e) {
                    log.warn("举报达到自动隐藏阈值但处置失败 reportId={} target={}:{} cause={}",
                            main.getId(), type, targetId, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("举报合并计数失败 mainId={} cause={}", main.getId(), e.toString());
        }
    }

    private static LocalDateTime slaDeadline(int priority) {
        return LocalDateTime.now().plusHours(priority == Report.PRIORITY_HIGH ? 2 : 24);
    }

    private static String reportNo() {
        return "R" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(10000));
    }

    public PageResult<ReportVO> my(Long userId, int pageNum, int pageSize) {
        Page<Report> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        Page<Report> result = reportMapper.selectPage(page, new QueryWrapper<Report>()
                .eq("reporter_id", userId).orderByDesc("create_time").orderByDesc("id"));
        return PageResult.of(result, this::toVO, false);
    }

    // ------------------------------------------------------------------ helpers

    private void checkSelfReport(Long userId, String type, Long targetId) {
        if (SocialTargets.USER.equals(type) && userId.equals(targetId)) {
            throw new BizException(ResultCode.REPORT_SELF_FORBIDDEN);
        }
        if (SocialTargets.COMMENT.equals(type)) {
            Comment comment = commentMapper.selectById(targetId);
            if (comment != null && userId.equals(comment.getUserId())) {
                throw new BizException(ResultCode.REPORT_SELF_FORBIDDEN);
            }
        }
        if (SocialTargets.MESSAGE.equals(type)) {
            PrivateMessage message = privateMessageMapper.selectById(targetId);
            if (message == null) {
                throw new BizException(ResultCode.REPORT_TARGET_NOT_FOUND);
            }
            if (!userId.equals(message.getSenderId()) && !userId.equals(message.getReceiverId())) {
                throw new BizException(ResultCode.REPORT_TARGET_NOT_FOUND);
            }
            if (userId.equals(message.getSenderId())) {
                throw new BizException(ResultCode.REPORT_SELF_FORBIDDEN);
            }
        }
    }

    private void checkDedup(Long userId, String type, String targetId, String reasonType,
                            LocalDateTime now) {
        long duplicate = reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("reporter_id", userId).eq("target_type", type).eq("target_id", targetId)
                .eq("reason_type", reasonType)
                .ge("create_time", now.minusHours(properties.getReport().getDedupHours())));
        if (duplicate > 0) {
            throw new BizException(ResultCode.REPORT_DUPLICATED);
        }
    }

    /** 近 30 天举报量达到阈值后检查有效率，防止恶意举报。 */
    private void checkMalicious(Long userId, LocalDateTime now) {
        LocalDateTime since = now.minusDays(30);
        long total = reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("reporter_id", userId).ge("create_time", since));
        if (total < properties.getReport().getMaliciousThreshold()) {
            return;
        }
        long valid = reportMapper.selectCount(new QueryWrapper<Report>()
                .eq("reporter_id", userId).eq("status", Report.STATUS_VALID)
                .ge("create_time", since));
        if (valid * 100 < total * properties.getReport().getMaliciousValidPercent()) {
            throw new BizException(ResultCode.REPORT_RESTRICTED);
        }
    }

    private static int priority(String type, String reasonType) {
        if (SocialTargets.MESSAGE.equals(type) || HIGH_PRIORITY_REASONS.contains(reasonType)) {
            return Report.PRIORITY_HIGH;
        }
        return Report.PRIORITY_NORMAL;
    }

    private String toJson(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

    private ReportVO toVO(Report report) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setTargetType(report.getTargetType());
        vo.setTargetId(report.getTargetId());
        vo.setTargetName(report.getTargetName());
        vo.setReasonType(report.getReasonType());
        vo.setReason(report.getReason());
        vo.setStatus(report.getStatus());
        vo.setHandleResult(report.getHandleResult());
        vo.setHandleReason(report.getHandleReason());
        vo.setActionTaken(report.getActionTaken());
        vo.setCreateTime(report.getCreateTime());
        vo.setHandledAt(report.getHandledAt());
        vo.setMergedIntoId(report.getMergedIntoId());
        return vo;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
