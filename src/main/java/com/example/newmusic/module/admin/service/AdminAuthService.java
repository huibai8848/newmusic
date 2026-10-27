package com.example.newmusic.module.admin.service;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.module.admin.dto.AdminLoginReq;
import com.example.newmusic.module.admin.dto.AdminPasswordChangeReq;
import com.example.newmusic.module.admin.entity.AdminUser;
import com.example.newmusic.module.admin.entity.LoginLog;
import com.example.newmusic.module.admin.mapper.AdminUserMapper;
import com.example.newmusic.module.admin.security.AdminCookieSupport;
import com.example.newmusic.module.admin.security.AdminJwtTokenProvider;
import com.example.newmusic.module.admin.security.AdminSessionStore;
import com.example.newmusic.module.admin.support.AdminCaptchaService;
import com.example.newmusic.module.admin.support.AdminIpMatcher;
import com.example.newmusic.module.admin.support.TotpSupport;
import com.example.newmusic.module.admin.vo.AdminLoginVO;
import com.example.newmusic.module.admin.vo.AdminUserVO;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 后台认证：图形验证码 → 密码 → IP 白名单 → TOTP。
 *
 * <p>失败计数以数据库为权威（{@code fail_count} / {@code lock_until}）：连续 5 次锁定 30 分钟，
 * 成功登录清零。密码与 TOTP 都通过后才检查账号状态，避免用错误信息枚举账号。
 */
@Slf4j
@Service
public class AdminAuthService {

    /** 用户不存在时也执行一次 BCrypt，避免响应时间泄露账号是否存在 */
    private static final String DUMMY_HASH =
            "$2a$10$ttdk9FzHXNcEIquDlmp4l.LCaJ1W/iVk6FGxQjqw2IJPgLO.bedO6";
    private static final int PASSWORD_ROTATION_DAYS = 90;

    private final AdminAccountService accountService;
    private final AdminUserMapper adminUserMapper;
    private final AdminCaptchaService captchaService;
    private final AdminSessionStore sessionStore;
    private final AdminJwtTokenProvider tokenProvider;
    private final AdminCookieSupport cookieSupport;
    private final TotpSupport totpSupport;
    private final PasswordEncoder passwordEncoder;
    private final LoginLogService loginLogService;

    public AdminAuthService(AdminAccountService accountService,
                            AdminUserMapper adminUserMapper,
                            AdminCaptchaService captchaService,
                            AdminSessionStore sessionStore,
                            AdminJwtTokenProvider tokenProvider,
                            AdminCookieSupport cookieSupport,
                            TotpSupport totpSupport,
                            PasswordEncoder passwordEncoder,
                            LoginLogService loginLogService) {
        this.accountService = accountService;
        this.adminUserMapper = adminUserMapper;
        this.captchaService = captchaService;
        this.sessionStore = sessionStore;
        this.tokenProvider = tokenProvider;
        this.cookieSupport = cookieSupport;
        this.totpSupport = totpSupport;
        this.passwordEncoder = passwordEncoder;
        this.loginLogService = loginLogService;
    }

    public AdminCaptchaService.Captcha captcha() {
        return captchaService.create();
    }

    public AdminLoginVO login(AdminLoginReq req, HttpServletRequest request, HttpServletResponse response) {
        sessionStore.requireAvailable();
        captchaService.verifyAndConsume(req.getCaptchaId(), req.getCaptchaCode());

        AdminUser user = accountService.getByUsername(req.getUsername());
        if (user == null) {
            passwordEncoder.matches(req.getPassword(), DUMMY_HASH);
            loginLogService.recordAdmin(null, req.getUsername(), LoginLog.RESULT_FAIL,
                    "账号不存在", true, request);
            throw new BizException(ResultCode.ADMIN_LOGIN_FAILED);
        }
        if (isLocked(user)) {
            loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_LOCKED,
                    "账号锁定中", true, request);
            throw new BizException(ResultCode.ADMIN_LOGIN_LOCKED);
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            int failures = recordFailure(user);
            loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                    "密码错误", failures >= 3, request);
            throw new BizException(ResultCode.ADMIN_LOGIN_FAILED);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                    "账号已禁用", false, request);
            throw new BizException(ResultCode.ADMIN_ACCOUNT_DISABLED);
        }
        String ip = IpUtil.getClientIp(request);
        if (!AdminIpMatcher.allows(user.getAllowIpList(), ip)) {
            loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                    "IP 不在白名单：" + ip, true, request);
            throw new BizException(ResultCode.ADMIN_IP_NOT_ALLOWED);
        }
        if (Integer.valueOf(1).equals(user.getTotpEnabled())) {
            if (!StringUtils.hasText(req.getTotpCode())) {
                throw new BizException(ResultCode.ADMIN_TOTP_REQUIRED);
            }
            if (!totpSupport.verify(user.getTotpSecret(), req.getTotpCode().trim())) {
                int failures = recordFailure(user);
                loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                        "TOTP 错误", failures >= 3, request);
                throw new BizException(ResultCode.ADMIN_TOTP_ERROR);
            }
        }

        adminUserMapper.update(null, new UpdateWrapper<AdminUser>()
                .eq("id", user.getId())
                .set("fail_count", 0)
                .set("lock_until", null)
                .set("last_login_time", LocalDateTime.now())
                .set("last_login_ip", ip));
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(ip);

        loginLogService.recordAdmin(user.getId(), user.getUsername(), LoginLog.RESULT_SUCCESS,
                null, false, request);
        long version = sessionStore.currentVersion(user.getId());
        log.info("后台登录成功 adminId={} username={} ip={}", user.getId(), user.getUsername(), ip);
        return issue(user, version, response);
    }

    /**
     * refresh 一次性轮换：旧 refresh 消费失败视为重放，直接递增会话版本踢掉所有设备。
     */
    public AdminLoginVO refresh(HttpServletRequest request, HttpServletResponse response) {
        sessionStore.requireAvailable();
        String refreshToken = cookieSupport.readRefresh(request);
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        Claims claims;
        try {
            claims = tokenProvider.parse(refreshToken);
        } catch (BizException e) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        if (!tokenProvider.isRefreshToken(claims)) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        Long adminId = tokenProvider.getAdminId(claims);
        Long consumedBy = sessionStore.consumeRefresh(tokenProvider.getTokenId(claims));
        if (consumedBy == null) {
            // 已轮换过的 refresh 再次出现：按泄露处理
            sessionStore.bumpVersion(adminId);
            log.warn("检测到后台 refreshToken 重放 adminId={}", adminId);
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        AdminUser user = accountService.getById(adminId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            sessionStore.revokeAllRefresh(adminId);
            throw new BizException(ResultCode.ADMIN_ACCOUNT_DISABLED);
        }
        long currentVersion = sessionStore.currentVersion(adminId);
        if (tokenProvider.getSessionVersion(claims) < currentVersion) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        return issue(user, currentVersion, response);
    }

    /** 退出登录：吊销当前 access，并递增会话版本使所有设备立即失效。 */
    public void logout(String accessToken, HttpServletResponse response) {
        if (StringUtils.hasText(accessToken)) {
            try {
                Claims claims = tokenProvider.parse(accessToken);
                sessionStore.revokeAccess(tokenProvider.getTokenId(claims),
                        tokenProvider.getRemainingSeconds(claims));
                sessionStore.bumpVersion(tokenProvider.getAdminId(claims));
            } catch (BizException e) {
                // Token 已失效也继续清 Cookie
                log.debug("退出登录时 Token 已失效: {}", e.getMessage());
            }
        }
        cookieSupport.clear(response);
    }

    public AdminUserVO me() {
        Long adminId = UserContext.requireUserId();
        AdminUser user = accountService.getById(adminId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ResultCode.ADMIN_ACCOUNT_DISABLED);
        }
        return toVO(user);
    }

    /**
     * 修改密码。成功后会话版本递增，随后重新签发一对 Token，避免用户被迫重新登录。
     */
    @Transactional
    public AdminLoginVO changePassword(AdminPasswordChangeReq req,
                                       HttpServletRequest request, HttpServletResponse response) {
        Long adminId = UserContext.requireUserId();
        AdminUser user = accountService.requireById(adminId);
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BizException(ResultCode.ADMIN_LOGIN_FAILED, "原密码不正确");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword())) {
            throw new BizException(ResultCode.ADMIN_PASSWORD_WEAK, "新密码不能与原密码相同");
        }
        accountService.validatePassword(req.getNewPassword(), true);
        adminUserMapper.update(null, new UpdateWrapper<AdminUser>()
                .eq("id", adminId)
                .set("password", passwordEncoder.encode(req.getNewPassword()))
                .set("password_update_time", LocalDateTime.now())
                .set("force_change_password", 0)
                .set("fail_count", 0)
                .set("lock_until", null));
        user.setPasswordUpdateTime(LocalDateTime.now());
        user.setForceChangePassword(0);
        long version = sessionStore.bumpVersion(adminId);
        loginLogService.recordAdmin(adminId, user.getUsername(), LoginLog.RESULT_SUCCESS,
                "修改密码", false, request);
        return issue(user, version, response);
    }

    // ------------------------------------------------------------------ helpers

    private AdminLoginVO issue(AdminUser user, long version, HttpServletResponse response) {
        LoginUser loginUser = accountService.buildLoginUser(user);
        String accessToken = tokenProvider.createAccessToken(loginUser, version);
        String refreshId = IdUtil.fastSimpleUUID();
        String refreshToken = tokenProvider.createRefreshToken(user.getId(), refreshId, version);
        sessionStore.registerRefresh(user.getId(), refreshId,
                Duration.ofSeconds(tokenProvider.getRefreshTokenTtl()));
        cookieSupport.writeTokens(response, accessToken, refreshToken);

        AdminLoginVO vo = new AdminLoginVO();
        vo.setAdmin(toVO(user, loginUser));
        vo.setExpiresIn(tokenProvider.getAccessTokenTtl());
        vo.setRefreshExpiresIn(tokenProvider.getRefreshTokenTtl());
        vo.setTotpEnabled(Integer.valueOf(1).equals(user.getTotpEnabled()));
        vo.setForceChangePassword(Integer.valueOf(1).equals(user.getForceChangePassword()));
        vo.setPasswordExpired(isPasswordExpired(user));
        return vo;
    }

    private AdminUserVO toVO(AdminUser user) {
        return toVO(user, accountService.buildLoginUser(user));
    }

    private AdminUserVO toVO(AdminUser user, LoginUser loginUser) {
        AdminUserVO vo = AdminUserVO.from(user);
        if (vo != null) {
            vo.setRoles(loginUser.getRoles());
            vo.setPerms(loginUser.getPerms());
        }
        return vo;
    }

    private boolean isLocked(AdminUser user) {
        return user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now());
    }

    private boolean isPasswordExpired(AdminUser user) {
        return user.getPasswordUpdateTime() != null
                && user.getPasswordUpdateTime().isBefore(LocalDateTime.now().minusDays(PASSWORD_ROTATION_DAYS));
    }

    /** 失败计数 +1；达到阈值则锁定 30 分钟并把计数清零（解锁后重新累计）。返回本次累计值。 */
    private int recordFailure(AdminUser user) {
        int current = Optional.ofNullable(user.getFailCount()).orElse(0);
        int next = current + 1;
        UpdateWrapper<AdminUser> update = new UpdateWrapper<AdminUser>().eq("id", user.getId());
        if (next >= AdminSessionStore.MAX_FAILURES) {
            update.set("fail_count", 0)
                    .set("lock_until", LocalDateTime.now().plus(AdminSessionStore.LOCK_DURATION));
            user.setFailCount(0);
            user.setLockUntil(LocalDateTime.now().plus(AdminSessionStore.LOCK_DURATION));
            log.warn("后台账号连续登录失败达到上限，锁定 {} 分钟 adminId={}",
                    AdminSessionStore.LOCK_DURATION.toMinutes(), user.getId());
        } else {
            update.set("fail_count", next);
            user.setFailCount(next);
        }
        adminUserMapper.update(null, update);
        return next;
    }
}
