package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.admin.dto.BanReq;
import com.example.newmusic.module.admin.service.AdminUserManageService;
import com.example.newmusic.module.contentsafety.dto.PermanentBanReq;
import com.example.newmusic.module.contentsafety.dto.RestrictReq;
import com.example.newmusic.module.contentsafety.entity.CreditLog;
import com.example.newmusic.module.contentsafety.entity.UserCredit;
import com.example.newmusic.module.contentsafety.entity.UserRestriction;
import com.example.newmusic.module.contentsafety.mapper.CreditLogMapper;
import com.example.newmusic.module.contentsafety.mapper.UserCreditMapper;
import com.example.newmusic.module.contentsafety.mapper.UserRestrictionMapper;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 用户处置与信用分（FR-27-007）。
 *
 * <p>L1 只警告并留一条「已释放」的处置记录，L2~L4 是生效中的限制，L5 永久封禁
 * 必须由超管在专用接口输入 BAN 二次确认。L4/L5 同步调用模块 09 的封禁能力
 * （踢下线 + 登录态失效），而不是只改自己的 restriction 表——否则用户仍能登录。
 */
@Slf4j
@Service
public class UserRestrictionService {

    private final UserRestrictionMapper restrictionMapper;
    private final UserCreditMapper creditMapper;
    private final CreditLogMapper creditLogMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final AdminUserManageService userManageService;
    private final RedisOps redisOps;

    public UserRestrictionService(UserRestrictionMapper restrictionMapper,
                                  UserCreditMapper creditMapper, CreditLogMapper creditLogMapper,
                                  UserMapper userMapper, NotificationService notificationService,
                                  AdminUserManageService userManageService, RedisOps redisOps) {
        this.restrictionMapper = restrictionMapper;
        this.creditMapper = creditMapper;
        this.creditLogMapper = creditLogMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.userManageService = userManageService;
        this.redisOps = redisOps;
    }

    @Transactional
    public UserRestriction restrict(Long userId, RestrictReq req) {
        Long adminId = UserContext.requireUserId();
        User user = requireUser(userId);
        if (user.getStatus() != null && user.getStatus() >= 3) {
            throw new BizException(ResultCode.PARAM_INVALID, "账号已注销，跳过处置");
        }
        int level = req.getLevel() == null ? 2 : req.getLevel();
        if (level == 5 || Integer.valueOf(UserRestriction.TYPE_PERMANENT_BAN).equals(req.getType())) {
            throw new BizException(ResultCode.PERMANENT_BAN_CONFIRM_REQUIRED, "永久封禁请使用专用接口并输入 BAN");
        }
        int type = req.getType() == null ? typeOfLevel(level) : req.getType();
        if (type == UserRestriction.TYPE_PERMANENT_BAN) {
            throw new BizException(ResultCode.PERMANENT_BAN_CONFIRM_REQUIRED);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean warning = level == 1;
        int defaultDays = defaultDays(level);
        Integer days = warning ? 0 : (req.getDays() == null ? defaultDays : req.getDays());
        LocalDateTime end = warning ? now : now.plusDays(days);

        UserRestriction restriction = new UserRestriction();
        restriction.setUserId(userId);
        restriction.setType(type);
        restriction.setLevel(level);
        restriction.setReason(req.getReason());
        restriction.setReasonCategory(req.getReasonCategory());
        restriction.setRelatedContentType(req.getRelatedContentType());
        restriction.setRelatedContentId(req.getRelatedContentId());
        restriction.setRelatedReportId(req.getRelatedReportId());
        restriction.setStartTime(now);
        restriction.setEndTime(end);
        restriction.setStatus(warning ? UserRestriction.STATUS_RELEASED : UserRestriction.STATUS_ACTIVE);
        restriction.setOperatorId(adminId);
        restriction.setRemark(req.getRemark());
        restriction.setCreateTime(now);
        restriction.setUpdateTime(now);
        restrictionMapper.insert(restriction);

        if (!warning && type == UserRestriction.TYPE_BAN) {
            BanReq ban = new BanReq();
            ban.setReason(req.getReason());
            ban.setBanUntil(end);
            userManageService.ban(Collections.singletonList(userId), ban, adminId);
        }
        applyCredit(userId, creditDelta(level, days), "违规处置 L" + level + "：" + req.getReason(),
                "RESTRICTION", restriction.getId());
        invalidate(userId);
        notifyUser(userId, level, type, req.getReason(), req.getReasonCategory(), end);
        log.info("用户处置完成 userId={} level={} type={} days={} adminId={}",
                userId, level, type, days, adminId);
        return restriction;
    }

    @Transactional
    public UserRestriction permanentBan(Long userId, PermanentBanReq req) {
        if (!isSuperAdmin()) {
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED, "永久封禁仅超级管理员可执行");
        }
        if (!"BAN".equals(req.getConfirm())) {
            throw new BizException(ResultCode.PERMANENT_BAN_CONFIRM_REQUIRED);
        }
        Long adminId = UserContext.requireUserId();
        if (adminId.equals(userId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能处置自己");
        }
        User user = requireUser(userId);
        if (user.getStatus() != null && user.getStatus() >= 3) {
            throw new BizException(ResultCode.PARAM_INVALID, "账号已注销，跳过处置");
        }
        LocalDateTime now = LocalDateTime.now();
        UserRestriction restriction = new UserRestriction();
        restriction.setUserId(userId);
        restriction.setType(UserRestriction.TYPE_PERMANENT_BAN);
        restriction.setLevel(5);
        restriction.setReason(req.getReason());
        restriction.setReasonCategory(req.getReasonCategory());
        restriction.setRelatedReportId(req.getRelatedReportId());
        restriction.setStartTime(now);
        restriction.setEndTime(null);
        restriction.setStatus(UserRestriction.STATUS_ACTIVE);
        restriction.setOperatorId(adminId);
        restriction.setCreateTime(now);
        restriction.setUpdateTime(now);
        restrictionMapper.insert(restriction);

        BanReq ban = new BanReq();
        ban.setReason(req.getReason());
        ban.setBanUntil(null);
        userManageService.ban(Collections.singletonList(userId), ban, adminId);
        applyCredit(userId, -100, "永久封禁：" + req.getReason(), "RESTRICTION", restriction.getId());
        invalidate(userId);
        notifyUser(userId, 5, UserRestriction.TYPE_PERMANENT_BAN, req.getReason(),
                req.getReasonCategory(), null);
        log.warn("永久封禁 userId={} adminId={} reason={}", userId, adminId, req.getReason());
        return restriction;
    }

    public List<UserRestriction> active(Long userId) {
        return restrictionMapper.selectActive(userId);
    }

    public List<UserRestriction> history(Long userId) {
        return restrictionMapper.selectList(new QueryWrapper<UserRestriction>()
                .eq("user_id", userId).orderByDesc("create_time").last("LIMIT 200"));
    }

    public UserRestriction activeOf(Long userId, int type) {
        for (UserRestriction restriction : restrictionMapper.selectActive(userId)) {
            if (Integer.valueOf(type).equals(restriction.getType())) {
                return restriction;
            }
        }
        return null;
    }

    public UserRestriction firstActive(Long userId) {
        List<UserRestriction> list = restrictionMapper.selectActive(userId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 评论/动态/弹幕/私信统一入口。 */
    public void requireCanPublish(Long userId) {
        UserRestriction active = firstActive(userId);
        if (active == null) {
            return;
        }
        if (active.getType() == UserRestriction.TYPE_BAN
                || active.getType() == UserRestriction.TYPE_PERMANENT_BAN
                || active.getType() == UserRestriction.TYPE_MUTE
                || active.getType() == UserRestriction.TYPE_COMMENT) {
            String until = active.getEndTime() == null ? "永久"
                    : active.getEndTime().toLocalDate().toString();
            throw new BizException(ResultCode.ACCOUNT_BANNED,
                    "账号已被限制发言，解除时间：" + until);
        }
    }

    public void requireCanUpload(Long userId) {
        for (UserRestriction active : restrictionMapper.selectActive(userId)) {
            if (active.getType() == UserRestriction.TYPE_BAN
                    || active.getType() == UserRestriction.TYPE_PERMANENT_BAN
                    || active.getType() == UserRestriction.TYPE_MUTE
                    || active.getType() == UserRestriction.TYPE_UPLOAD) {
                throw new BizException(ResultCode.ACCOUNT_BANNED, "账号已被限制上传内容");
            }
        }
    }

    /** 信用分影响能力（BR-27-007）。 */
    public boolean canPublishByCredit(Long userId) {
        UserCredit credit = creditMapper.selectById(userId);
        return credit == null || credit.getScore() == null || credit.getScore() >= 30;
    }

    @Transactional
    public UserCredit credit(Long userId) {
        creditMapper.ensure(userId);
        UserCredit credit = creditMapper.selectById(userId);
        redisOps.set(RedisKeys.auditCredit(userId), String.valueOf(credit.getScore()),
                Duration.ofDays(1));
        return credit;
    }

    public List<CreditLog> creditLogs(Long userId) {
        return creditLogMapper.selectList(new QueryWrapper<CreditLog>()
                .eq("user_id", userId).orderByDesc("create_time").last("LIMIT 200"));
    }

    @Transactional
    public UserCredit adjustCredit(Long userId, int delta, String reason, String relatedType,
                                   Long relatedId) {
        requireUser(userId);
        return applyCredit(userId, delta, reason, relatedType, relatedId);
    }

    /** 内容被删除扣 3 分；申诉成功时反向补回。 */
    @Transactional
    public void contentPenalty(Long userId, Long contentId, String contentType) {
        if (userId == null) {
            return;
        }
        applyCredit(userId, -3, "内容违规被处置：" + contentType + " #" + contentId,
                contentType, contentId);
    }

    @Transactional
    public void restoreCredit(Long userId, int points, String reason, Long relatedId) {
        applyCredit(userId, Math.abs(points), reason, "APPEAL", relatedId);
    }

    private UserCredit applyCredit(Long userId, int delta, String reason,
                                   String relatedType, Long relatedId) {
        creditMapper.ensure(userId);
        UserCredit credit = creditMapper.selectForUpdate(userId);
        if (credit == null) {
            return null;
        }
        int before = credit.getScore() == null ? UserCredit.INITIAL_SCORE : credit.getScore();
        int after = Math.max(0, Math.min(100, before + delta));
        LocalDateTime now = LocalDateTime.now();
        credit.setScore(after);
        if (delta < 0) {
            int count = credit.getViolationCount90d() == null ? 0 : credit.getViolationCount90d();
            if (credit.getLastViolationTime() != null
                    && credit.getLastViolationTime().isBefore(now.minusDays(90))) {
                count = 0;
            }
            credit.setViolationCount90d(count + 1);
            credit.setLastViolationTime(now);
        }
        credit.setUpdateTime(now);
        creditMapper.updateById(credit);

        CreditLog log = new CreditLog();
        log.setUserId(userId);
        log.setChangeScore(after - before);
        log.setBeforeScore(before);
        log.setAfterScore(after);
        log.setReason(reason);
        log.setRelatedType(relatedType);
        log.setRelatedId(relatedId);
        log.setCreateTime(now);
        creditLogMapper.insert(log);
        redisOps.set(RedisKeys.auditCredit(userId), String.valueOf(after), Duration.ofDays(1));
        return credit;
    }

    /** 申诉成功：撤销处置；封禁类同时解封并恢复登录。 */
    @Transactional
    public void releaseForAppeal(UserRestriction restriction) {
        restrictionMapper.release(restriction.getId());
        boolean banned = restriction.getType() == UserRestriction.TYPE_BAN
                || restriction.getType() == UserRestriction.TYPE_PERMANENT_BAN;
        if (banned) {
            boolean stillBanned = false;
            for (UserRestriction active : restrictionMapper.selectActive(restriction.getUserId())) {
                if (active.getType() == UserRestriction.TYPE_BAN
                        || active.getType() == UserRestriction.TYPE_PERMANENT_BAN) {
                    stillBanned = true;
                    break;
                }
            }
            if (!stillBanned) {
                userManageService.unban(restriction.getUserId(), UserContext.requireUserId());
            }
        }
        invalidate(restriction.getUserId());
    }

    /** 管理端主动解除限制（不是申诉撤销）。封禁类同步解封。 */
    @Transactional
    public UserRestriction release(Long userId, Long restrictionId, String reason) {
        if (!isSuperAdmin()) {
            // 解除封禁等同于撤销处置，普通审核员不能自行放行。
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED, "仅超级管理员可解除处置");
        }
        UserRestriction restriction = restrictionMapper.selectById(restrictionId);
        if (restriction == null || !userId.equals(restriction.getUserId())) {
            throw new BizException(ResultCode.RESTRICTION_NOT_FOUND);
        }
        if (!Integer.valueOf(UserRestriction.STATUS_ACTIVE).equals(restriction.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "该处置已解除或已到期");
        }
        String suffix = " [管理员解除]" + (StringUtils.hasText(reason) ? "：" + reason : "");
        if (restrictionMapper.releaseManual(restrictionId, suffix) == 0) {
            throw new BizException(ResultCode.RESTRICTION_NOT_FOUND);
        }
        if (restriction.getType() == UserRestriction.TYPE_BAN
                || restriction.getType() == UserRestriction.TYPE_PERMANENT_BAN) {
            boolean stillBanned = false;
            for (UserRestriction active : restrictionMapper.selectActive(userId)) {
                if (active.getType() == UserRestriction.TYPE_BAN
                        || active.getType() == UserRestriction.TYPE_PERMANENT_BAN) {
                    stillBanned = true;
                    break;
                }
            }
            if (!stillBanned) {
                userManageService.unban(userId, UserContext.requireUserId());
            }
        }
        invalidate(userId);
        try {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setNotifyType(Notification.TYPE_SYSTEM);
            notification.setTitle("处置已解除");
            notification.setContent("您的一项账号限制已被管理员解除"
                    + (StringUtils.hasText(reason) ? "：" + reason : ""));
            notification.setCreateTime(LocalDateTime.now());
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("解除处置通知失败 userId={} cause={}", userId, e.toString());
        }
        log.info("管理端解除处置 userId={} restrictionId={} operator={}",
                userId, restrictionId, UserContext.getUserId());
        return restrictionMapper.selectById(restrictionId);
    }

    public static int creditPointsForLevel(Integer level, Integer days) {
        if (level == null) {
            return 5;
        }
        switch (level) {
            case 1:
                return 5;
            case 2:
                return 10;
            case 3:
                return days != null && days >= 7 ? 30 : 20;
            case 4:
                return 50;
            case 5:
                return 100;
            default:
                return 5;
        }
    }

    /** 定时任务：到期处置状态置为已过期，封禁用户同步解封。 */
    @Transactional
    public int releaseExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<UserRestriction> expired = restrictionMapper.selectExpired(now);
        if (expired.isEmpty()) {
            return 0;
        }
        int affected = restrictionMapper.expireDue(now);
        for (UserRestriction restriction : expired) {
            if (restriction.getType() == UserRestriction.TYPE_BAN
                    || restriction.getType() == UserRestriction.TYPE_PERMANENT_BAN) {
                boolean stillBanned = false;
                for (UserRestriction active : restrictionMapper.selectActive(restriction.getUserId())) {
                    if (active.getType() == UserRestriction.TYPE_BAN
                            || active.getType() == UserRestriction.TYPE_PERMANENT_BAN) {
                        stillBanned = true;
                        break;
                    }
                }
                if (!stillBanned) {
                    try {
                        userManageService.unban(restriction.getUserId(), 0L);
                    } catch (Exception e) {
                        log.warn("自动解除封禁失败 userId={} cause={}",
                                restriction.getUserId(), e.toString());
                    }
                }
            }
            invalidate(restriction.getUserId());
        }
        log.info("自动解除到期处置 {} 条", affected);
        return affected;
    }

    /** 每月 +5，上限 100（BR-27-084）。 */
    @Transactional
    public int recoverMonthly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusDays(30);
        List<UserCredit> credits = creditMapper.selectList(new QueryWrapper<UserCredit>()
                .lt("score", 100)
                .and(w -> w.isNull("last_recover_time").or().le("last_recover_time", deadline))
                .last("LIMIT 500"));
        int count = 0;
        for (UserCredit credit : credits) {
            applyCredit(credit.getUserId(), 5, "连续 30 天无违规，信用分自然恢复",
                    "RECOVER", null);
            UserCredit update = new UserCredit();
            update.setUserId(credit.getUserId());
            update.setLastRecoverTime(now);
            creditMapper.updateById(update);
            count++;
        }
        if (count > 0) {
            log.info("信用分自然恢复 {} 个用户", count);
        }
        return count;
    }

    private void notifyUser(Long userId, int level, int type, String reason,
                            String reasonCategory, LocalDateTime end) {
        try {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setNotifyType(Notification.TYPE_BAN);
            notification.setTitle(level >= 4 ? "账号处置通知" : "违规提醒");
            String content;
            if (level == 1) {
                content = "您的账号收到一次违规警告，请遵守社区规范。";
            } else if (level == 5) {
                content = "您的账号已被永久封禁，如有异议可在 7 天内提起申诉。";
            } else {
                content = "您的账号因违规被" + actionName(type, level)
                        + "，解除时间：" + (end == null ? "永久" : end.toLocalDate())
                        + "。如有异议可在 7 天内提起申诉。";
            }
            // 涉政类不透露具体原因（BR-27-076）
            if (reasonCategory != null && !"POLITICS".equalsIgnoreCase(reasonCategory)
                    && StringUtils.hasText(reason)) {
                content = content + " 原因：" + reason;
            }
            notification.setContent(content);
            notification.setCreateTime(LocalDateTime.now());
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("处置通知失败 userId={} cause={}", userId, e.toString());
        }
    }

    private static String actionName(int type, int level) {
        switch (type) {
            case UserRestriction.TYPE_COMMENT:
                return "限制发言";
            case UserRestriction.TYPE_MUTE:
                return "禁言";
            case UserRestriction.TYPE_BAN:
                return "封禁";
            case UserRestriction.TYPE_UPLOAD:
                return "限制上传";
            default:
                return "处置（L" + level + "）";
        }
    }

    private static int typeOfLevel(int level) {
        switch (level) {
            case 1:
            case 2:
                return UserRestriction.TYPE_COMMENT;
            case 3:
                return UserRestriction.TYPE_MUTE;
            case 4:
            case 5:
                return UserRestriction.TYPE_BAN;
            default:
                return UserRestriction.TYPE_COMMENT;
        }
    }

    private static int defaultDays(int level) {
        switch (level) {
            case 1:
                return 0;
            case 2:
                return 7;
            case 3:
                return 7;
            case 4:
                return 30;
            default:
                return 7;
        }
    }

    private static int creditDelta(int level, int days) {
        switch (level) {
            case 1:
                return -5;
            case 2:
                return -10;
            case 3:
                return days >= 7 ? -30 : -20;
            case 4:
                return -50;
            case 5:
                return -100;
            default:
                return -5;
        }
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "用户不存在");
        }
        return user;
    }

    private static boolean isSuperAdmin() {
        return UserContext.get() != null && UserContext.get().isSuperAdmin();
    }

    private void invalidate(Long userId) {
        redisOps.delete(RedisKeys.auditRestriction(userId));
        redisOps.delete(RedisKeys.auditCredit(userId));
    }
}
