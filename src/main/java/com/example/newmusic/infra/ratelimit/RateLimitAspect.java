package com.example.newmusic.infra.ratelimit;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * {@link RateLimit} 的织入实现。
 *
 * <p>两种豁免（BR-12-027，见系分 D-22）：
 * <ul>
 *   <li>配置的 IP 白名单——为空是默认值。**刻意不把 127.0.0.1 写进默认白名单**：
 *       本机冒烟就是从这里发起的，加了白名单等于让限流永远无法被验证；</li>
 *   <li>超管（持有 {@code *:*:*}）——运维排障时不能把自己限死在门外。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final RateLimitService rateLimitService;

    public RateLimitAspect(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非 HTTP 上下文（定时任务、单元测试直接调方法）：无维度可言，直接放行
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        String identifier = resolveIdentifier(rateLimit.dimension(), request);
        if (rateLimitService.isWhitelisted(IpUtil.getClientIp(request)) || isSuperAdmin() || identifier == null) {
            return joinPoint.proceed();
        }

        String api = request.getMethod() + ':' + request.getRequestURI();
        try {
            // 判定、写 Retry-After、抛 10004 都在 enforce 里，与业务代码的显式调用共用一条路径
            rateLimitService.enforce(rateLimit.type(), rateLimit.dimension(), identifier,
                    rateLimit.limit(), rateLimit.windowSeconds(), api, rateLimit.message());
        } catch (BizException e) {
            if (e.getCode() == ResultCode.RATE_LIMITED.getCode()) {
                log.warn("触发限流 dimension={} identifier={} api={} limit={}/{}s",
                        rateLimit.dimension(), identifier, api, rateLimit.limit(), rateLimit.windowSeconds());
            }
            throw e;
        }
        return joinPoint.proceed();
    }

    private String resolveIdentifier(RateLimitDimension dimension, HttpServletRequest request) {
        LoginUser loginUser = UserContext.get();
        switch (dimension) {
            case USER:
                // 未登录时退回 IP：否则游客会绕过所有 USER 维度的限制
                return loginUser != null ? String.valueOf(loginUser.getUserId())
                        : IpUtil.getClientIp(request);
            case GLOBAL:
                return "global";
            case ACCOUNT:
            case IP:
            default:
                return IpUtil.getClientIp(request);
        }
    }

    private boolean isSuperAdmin() {
        LoginUser loginUser = UserContext.get();
        return loginUser != null && loginUser.isSuperAdmin();
    }
}
