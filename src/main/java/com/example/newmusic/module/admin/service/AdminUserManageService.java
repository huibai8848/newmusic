package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.security.TokenSessionStore;
import com.example.newmusic.module.admin.dto.AdminUserEditReq;
import com.example.newmusic.module.admin.dto.AdminUserQueryReq;
import com.example.newmusic.module.admin.dto.BanReq;
import com.example.newmusic.module.admin.vo.AdminCUserVO;
import com.example.newmusic.module.admin.vo.SensitivePhoneVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台-C 端用户管理。
 *
 * <p>封禁是「三写」：DB 状态（列表/详情权威）、Redis 封禁标记（每请求即时拦截）、
 * 撤销全部会话（refreshToken 立刻失效）。三者顺序为 DB → Redis → 通知，
 * 通知失败不影响封禁结果。
 */
@Slf4j
@Service
public class AdminUserManageService {

    /** 永久封禁在 Redis 中的 TTL（100 年）；DB 端 ban_until 为 NULL 表示永久 */
    private static final Duration PERMANENT_BAN = Duration.ofDays(36500);
    private static final int MAX_BATCH = 100;
    private static final String PASSWORD_POOL =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final TokenSessionStore tokenSessionStore;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;

    public AdminUserManageService(UserService userService,
                                  TokenSessionStore tokenSessionStore,
                                  NotificationService notificationService,
                                  PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.tokenSessionStore = tokenSessionStore;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
    }

    public PageResult<AdminCUserVO> page(AdminUserQueryReq query) {
        query.normalize();
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(q -> q.like("username", kw)
                    .or().like("nickname", kw)
                    .or().like("phone", kw));
        }
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getUserType() != null) {
            wrapper.eq("user_type", query.getUserType());
        }
        if (query.getBanned() != null) {
            if (query.getBanned()) {
                wrapper.eq("status", 2);
            } else {
                wrapper.ne("status", 2);
            }
        }
        wrapper.orderByDesc("create_time");
        Page<User> page = userService.page(query.toPage(), wrapper);
        return PageResult.of(page, AdminCUserVO::from, query.isTruncated());
    }

    public AdminCUserVO detail(Long userId) {
        return AdminCUserVO.from(userService.getByIdOrThrow(userId));
    }

    /** 完整手机号：调用方必须已通过 {@code admin:user:phone:view} 且被 {@code @AdminOpLog} 留痕。 */
    public SensitivePhoneVO phone(Long userId) {
        User user = userService.getByIdOrThrow(userId);
        SensitivePhoneVO vo = new SensitivePhoneVO();
        vo.setUserId(user.getId());
        vo.setPhone(user.getPhone());
        return vo;
    }

    @Transactional
    public AdminCUserVO edit(Long userId, AdminUserEditReq req, Long operatorId) {
        User user = userService.getByIdOrThrow(userId);
        if (StringUtils.hasText(req.getNickname())
                && !req.getNickname().equals(user.getNickname())) {
            long exists = userService.count(new QueryWrapper<User>().eq("nickname", req.getNickname()));
            if (exists > 0) {
                throw new BizException(ResultCode.NICKNAME_EXISTS);
            }
            user.setNickname(req.getNickname());
        }
        if (req.getEmail() != null) {
            user.setEmail(req.getEmail());
        }
        if (req.getUserType() != null) {
            // 后台管理员身份在 t_admin_user，与 C 端 userType 无关；
            // 允许改成 3/4 只会造成「看起来是管理员但没有任何后台权限」的误解
            if (req.getUserType() != 1 && req.getUserType() != 2) {
                throw new BizException(ResultCode.PARAM_INVALID, "用户类型只能设为 1（普通）或 2（运营）");
            }
            user.setUserType(req.getUserType());
        }
        user.setUpdateBy(operatorId);
        userService.updateById(user);
        return AdminCUserVO.from(userService.getById(userId));
    }

    /** 批量封禁（单次 ≤100）。重复封禁按更新处理，幂等。 */
    @Transactional
    public int ban(List<Long> ids, BanReq req, Long operatorId) {
        List<Long> targets = normalizeIds(ids);
        Duration ttl = PERMANENT_BAN;
        if (req.getBanUntil() != null) {
            if (!req.getBanUntil().isAfter(LocalDateTime.now())) {
                throw new BizException(ResultCode.PARAM_INVALID, "解封时间必须晚于当前时间");
            }
            ttl = Duration.between(LocalDateTime.now(), req.getBanUntil());
        }
        int affected = 0;
        for (Long userId : targets) {
            User user = userService.getById(userId);
            if (user == null) {
                continue;
            }
            userService.update(new UpdateWrapper<User>()
                    .eq("id", userId)
                    .set("status", 2)
                    .set("ban_reason", req.getReason())
                    .set("ban_until", req.getBanUntil())
                    .set("update_by", operatorId));
            tokenSessionStore.ban(userId, req.getReason(), ttl);
            notify(userId, "账号封禁通知", banMessage(req));
            affected++;
            log.info("后台封禁用户 userId={} until={} by={}", userId, req.getBanUntil(), operatorId);
        }
        return affected;
    }

    @Transactional
    public void unban(Long userId, Long operatorId) {
        User user = userService.getByIdOrThrow(userId);
        userService.update(new UpdateWrapper<User>()
                .eq("id", userId)
                .set("status", 1)
                .set("ban_reason", null)
                .set("ban_until", null)
                .set("update_by", operatorId));
        tokenSessionStore.unban(userId);
        notify(userId, "账号解封通知", "您的账号已解除封禁，可以正常登录使用。");
        log.info("后台解封用户 userId={} by={} wasStatus={}", userId, operatorId, user.getStatus());
    }

    /** 重置 C 端密码：返回一次性临时密码，并强制下次登录修改。 */
    @Transactional
    public String resetPassword(Long userId, Long operatorId) {
        userService.getByIdOrThrow(userId);
        String password = randomPassword();
        userService.update(new UpdateWrapper<User>()
                .eq("id", userId)
                .set("password", passwordEncoder.encode(password))
                .set("force_change_password", 1)
                .set("update_by", operatorId));
        tokenSessionStore.revokeAllSessions(userId);
        notify(userId, "密码重置通知", "管理员已重置您的登录密码，请使用临时密码登录并立即修改。");
        log.info("后台重置用户密码 userId={} by={}", userId, operatorId);
        return password;
    }

    /**
     * 自动解封到期的临时封禁。由定时任务调用；返回处理条数。
     *
     * <p>只处理 {@code ban_until IS NOT NULL AND ban_until <= NOW()}，
     * 永久封禁（ban_until 为 NULL）永远不会被这条 SQL 命中。
     */
    @Transactional
    public int unbanExpired() {
        List<User> expired = userService.list(new QueryWrapper<User>()
                .eq("status", 2)
                .isNotNull("ban_until")
                .le("ban_until", LocalDateTime.now())
                .last("LIMIT 500"));
        int count = 0;
        for (User user : expired) {
            userService.update(new UpdateWrapper<User>()
                    .eq("id", user.getId())
                    .eq("status", 2)
                    .set("status", 1)
                    .set("ban_reason", null)
                    .set("ban_until", null));
            tokenSessionStore.unban(user.getId());
            notify(user.getId(), "账号解封通知", "封禁期限已到，您的账号已自动恢复。");
            count++;
        }
        if (count > 0) {
            log.info("自动解封到期用户 {} 个", count);
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请选择用户");
        }
        List<Long> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        if (distinct.size() > MAX_BATCH) {
            throw new BizException(ResultCode.PARAM_INVALID, "单次最多封禁 " + MAX_BATCH + " 个用户");
        }
        return distinct;
    }

    private String banMessage(BanReq req) {
        if (req.getBanUntil() == null) {
            return "您的账号因「" + req.getReason() + "」被永久封禁。";
        }
        return "您的账号因「" + req.getReason() + "」被封禁，解封时间："
                + req.getBanUntil() + "。";
    }

    private void notify(Long userId, String title, String content) {
        try {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setNotifyType(Notification.TYPE_SYSTEM);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setIsRead(0);
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("后台用户通知发送失败 userId={} title={} cause={}", userId, title, e.toString());
        }
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PASSWORD_POOL.charAt(RANDOM.nextInt(PASSWORD_POOL.length())));
        }
        return sb.toString();
    }
}
