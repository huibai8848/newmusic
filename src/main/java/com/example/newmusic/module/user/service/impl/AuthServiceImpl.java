package com.example.newmusic.module.user.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.infra.ratelimit.RateLimitService;
import com.example.newmusic.infra.security.JwtTokenProvider;
import com.example.newmusic.infra.security.TokenSessionStore;
import com.example.newmusic.module.admin.entity.LoginLog;
import com.example.newmusic.module.admin.service.LoginLogService;
import com.example.newmusic.module.user.dto.LoginReq;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.AuthService;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.user.vo.LoginVO;
import com.example.newmusic.module.user.vo.UserVO;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenSessionStore tokenSessionStore;
    private final RateLimitService rateLimitService;
    private final LoginLogService loginLogService;

    public AuthServiceImpl(UserService userService, PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider, TokenSessionStore tokenSessionStore,
                           RateLimitService rateLimitService, LoginLogService loginLogService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.tokenSessionStore = tokenSessionStore;
        this.rateLimitService = rateLimitService;
        this.loginLogService = loginLogService;
    }

    @Override
    public LoginVO login(LoginReq req, String clientIp) {
        // 账号维度限流（10 次/分钟）。IP 维度由 AuthController 上的 @RateLimit 覆盖；
        // 两者互补——IP 维度挡不住代理池，账号维度才是撞库的真实防线。
        // 放在服务里而不是注解上，是因为标识（账号）来自请求体，注解取不到
        rateLimitService.enforce("auth", RateLimitDimension.ACCOUNT, req.getAccount(),
                10, 60, "POST:/api/v1/auth/login", null);

        // 登录属于「签发类」操作：拿不到 Redis 就不能保证 refreshToken 的轮换语义，宁可拒绝（90003）
        tokenSessionStore.requireAvailable();

        // 先看锁定状态，再查库/验密码：锁定期内连密码都不校验，
        // 顺带消掉了「锁定与不存在的账号响应耗时不同」这个账号枚举侧信道
        if (tokenSessionStore.isLoginLocked(req.getAccount())) {
            long remaining = tokenSessionStore.loginLockRemainingSeconds(req.getAccount());
            log.warn("账号处于锁定期，拒绝登录 account={} 剩余={}s", maskAccount(req.getAccount()), remaining);
            recordLoginLog(null, maskAccount(req.getAccount()), LoginLog.RESULT_LOCKED,
                    "账号处于锁定期", true);
            throw new BizException(ResultCode.LOGIN_FAILED, lockMessage(remaining));
        }

        User user = findByAccount(req.getAccount());
        if (user == null) {
            log.warn("登录失败：账号不存在 account={}", maskAccount(req.getAccount()));
            int failures = recordFailure(req.getAccount());
            recordLoginLog(null, maskAccount(req.getAccount()), LoginLog.RESULT_FAIL,
                    "账号或密码错误", failures >= 3);
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        // 状态异常（含封禁）不算「密码错误」，不计入失败次数：否则被封禁的用户
        // 反复点登录会把计数刷高，掩盖真正的撞库信号
        try {
            checkStatus(user);
        } catch (BizException e) {
            recordLoginLog(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                    e.getMessage(), false);
            throw e;
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("登录失败：密码错误 userId={}", user.getId());
            int failures = recordFailure(req.getAccount());
            recordLoginLog(user.getId(), user.getUsername(), LoginLog.RESULT_FAIL,
                    "账号或密码错误", failures >= 3);
            throw new BizException(ResultCode.LOGIN_FAILED);
        }

        tokenSessionStore.clearLoginFailures(req.getAccount());
        LoginUser loginUser = userService.buildLoginUser(user.getId());
        recordLogin(user.getId(), clientIp);
        recordLoginLog(user.getId(), user.getUsername(), LoginLog.RESULT_SUCCESS, null, false);

        log.info("登录成功 userId={} account={}", user.getId(), maskAccount(req.getAccount()));
        return buildLoginVO(loginUser);
    }

    @Override
    public LoginVO refresh(String refreshToken) {
        tokenSessionStore.requireAvailable();

        Claims claims = tokenProvider.parse(refreshToken);
        if (!tokenProvider.isRefreshToken(claims)) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        // 轮换：把旧 refreshToken 从账本上消费掉。返回 null 有两种可能——它早就过期了，
        // 或者它**已经被用过一次**。后者意味着这份凭证可能已经泄露（攻击者拿到后抢先用掉，
        // 或者反过来），所以不能只是拒绝，必须把该用户的全部会话一并撤销
        Long userId = tokenSessionStore.consumeRefresh(tokenProvider.getTokenId(claims));
        if (userId == null) {
            Long claimedUserId = tokenProvider.getUserId(claims);
            log.warn("refreshToken 无效或已被使用，撤销该用户全部会话 userId={}", claimedUserId);
            tokenSessionStore.revokeAllSessions(claimedUserId);
            throw new BizException(ResultCode.TOKEN_INVALID, "登录凭证已失效，请重新登录");
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
        checkStatus(user);
        return buildLoginVO(userService.buildLoginUser(userId));
    }

    @Override
    public void logout(String accessToken) {
        Long userId = UserContext.getUserId();
        // 登出是安全操作：写不进黑名单就必须报错，不能让用户以为「已经退出了」
        tokenSessionStore.requireAvailable();

        try {
            Claims claims = tokenProvider.parse(accessToken);
            tokenSessionStore.revokeAccessToken(tokenProvider.getTokenId(claims),
                    tokenProvider.getRemainingSeconds(claims));
        } catch (BizException e) {
            // Token 已过期或非法：它本来就已经失效了，登出按成功处理（幂等）
            log.debug("登出时 Token 不可解析，跳过黑名单: {}", e.getMessage());
        }
        if (userId != null) {
            tokenSessionStore.revokeAllSessions(userId);
        }
        log.info("用户登出 userId={}", userId);
    }

    /** 记一次失败并在达到阈值时告警；复用 20001 以免暴露「账号是否存在」（见 5.2 的说明）。 */
    private int recordFailure(String account) {
        int failures = tokenSessionStore.recordLoginFailure(account);
        if (failures >= TokenSessionStore.MAX_LOGIN_FAILURES) {
            log.warn("账号连续登录失败 {} 次，已锁定 {} 分钟 account={}",
                    failures, TokenSessionStore.LOGIN_FAIL_WINDOW.toMinutes(), maskAccount(account));
        }
        return failures;
    }

    /**
     * 写 C 端登录日志（模块 09）。请求对象从 {@link RequestContextHolder} 取：
     * AuthService 的接口签名不带 request，而登录日志需要 IP / UA；日志写失败不影响登录结果。
     */
    private void recordLoginLog(Long userId, String username, int result,
                                String failReason, boolean suspicious) {
        HttpServletRequest request = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        }
        loginLogService.recordC(userId, username, result, failReason, suspicious, request);
    }

    private String lockMessage(long remainingSeconds) {
        long minutes = Math.max(1, (remainingSeconds + 59) / 60);
        return "登录失败次数过多，请 " + minutes + " 分钟后再试";
    }

    private LoginVO buildLoginVO(LoginUser loginUser) {
        UserVO userVO = userService.toVO(userService.getByIdOrThrow(loginUser.getUserId()), false);
        userVO.setRoles(loginUser.getRoles());
        userVO.setPerms(loginUser.getPerms());

        // refreshToken 的 jti 由这里决定：签发前就要把它记进 Redis 那本「一次性账本」，
        // 所以不能签完再从 Token 里反解
        String refreshTokenId = IdUtil.fastSimpleUUID();

        LoginVO vo = new LoginVO();
        vo.setAccessToken(tokenProvider.createAccessToken(loginUser));
        vo.setRefreshToken(tokenProvider.createRefreshToken(loginUser.getUserId(), refreshTokenId));
        vo.setExpiresIn(tokenProvider.getAccessTokenTtl());
        vo.setUser(userVO);

        tokenSessionStore.registerRefresh(loginUser.getUserId(), refreshTokenId,
                Duration.ofSeconds(tokenProvider.getRefreshTokenTtl()));
        return vo;
    }

    private User findByAccount(String account) {
        User user = userService.getByUsername(account);
        if (user == null && PHONE_PATTERN.matcher(account).matches()) {
            user = userService.getOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getPhoneHash, DigestUtil.sha256Hex(account)), false);
        }
        return user;
    }

    private void checkStatus(User user) {
        Integer status = user.getStatus();
        if (status != null && status == 2) {
            String reason = StrUtil.blankToDefault(user.getBanReason(), "违反社区规范");
            String until = user.getBanUntil() == null ? "永久" : user.getBanUntil().toString();
            throw new BizException(ResultCode.ACCOUNT_BANNED, "账号已被封禁：" + reason + "（解封时间：" + until + "）");
        }
        if (status == null || status != 1) {
            throw new BizException(ResultCode.ACCOUNT_STATUS_ABNORMAL);
        }
    }

    private void recordLogin(Long userId, String clientIp) {
        User update = new User();
        update.setId(userId);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(clientIp);
        userService.updateById(update);
    }

    /** 日志脱敏（《05-通用设计规范》7.3 禁止打印完整手机号）。 */
    private String maskAccount(String account) {
        if (account == null || account.length() < 4) {
            return "***";
        }
        if (PHONE_PATTERN.matcher(account).matches()) {
            return account.substring(0, 3) + "****" + account.substring(7);
        }
        return account.charAt(0) + "***" + account.charAt(account.length() - 1);
    }
}
