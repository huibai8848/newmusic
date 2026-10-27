package com.example.newmusic.module.admin.service;

import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.module.admin.entity.LoginLog;
import com.example.newmusic.module.admin.mapper.LoginLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * 登录日志（C 端 + 后台）。写失败只记 WARN：登录结果不能因为日志表故障而反转。
 *
 * <p>这里**不使用 {@code @Async}**：入口方法需要通过 self-invocation 转发到私有插入方法，
 * Spring 代理对同类内部调用不生效。改为显式提交到 {@code adminLogExecutor}，
 * 且在提交前就把 IP / UA 取出来（请求对象响应结束后可能已被回收）。
 */
@Slf4j
@Service
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;
    private final Executor executor;

    public LoginLogService(LoginLogMapper loginLogMapper,
                           @Qualifier("adminLogExecutor") Executor executor) {
        this.loginLogMapper = loginLogMapper;
        this.executor = executor;
    }

    public void recordAdmin(Long userId, String username, Integer result,
                            String failReason, boolean suspicious, HttpServletRequest request) {
        submit(userId, username, LoginLog.TYPE_ADMIN, result, failReason, suspicious, request);
    }

    public void recordC(Long userId, String username, Integer result,
                        String failReason, boolean suspicious, HttpServletRequest request) {
        submit(userId, username, LoginLog.TYPE_C, result, failReason, suspicious, request);
    }

    private void submit(Long userId, String username, int loginType, Integer result,
                        String failReason, boolean suspicious, HttpServletRequest request) {
        final String ip = IpUtil.getClientIp(request);
        final String userAgent = truncate(request == null ? null : request.getHeader("User-Agent"), 512);
        final String device = deviceOf(userAgent);

        LoginLog entry = new LoginLog();
        entry.setUserId(userId);
        entry.setUsername(truncate(username, 64));
        entry.setLoginType(loginType);
        entry.setIp(truncate(ip, 64));
        entry.setUserAgent(userAgent);
        entry.setDevice(device);
        entry.setResult(result);
        entry.setFailReason(truncate(failReason, 200));
        entry.setSuspicious(suspicious ? 1 : 0);
        entry.setCreateTime(LocalDateTime.now());

        try {
            executor.execute(() -> insert(entry));
        } catch (Exception e) {
            log.warn("登录日志排队失败 username={} type={} cause={}", username, loginType, e.toString());
        }
    }

    private void insert(LoginLog entry) {
        try {
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("登录日志写入失败 username={} type={} cause={}",
                    entry.getUsername(), entry.getLoginType(), e.toString());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String deviceOf(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return null;
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "mobile";
        }
        return "web";
    }
}
