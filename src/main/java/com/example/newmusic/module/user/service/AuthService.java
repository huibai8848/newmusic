package com.example.newmusic.module.user.service;

import com.example.newmusic.module.user.dto.LoginReq;
import com.example.newmusic.module.user.vo.LoginVO;

/**
 * 认证服务：登录 / 刷新 / 登出。
 */
public interface AuthService {

    LoginVO login(LoginReq req, String clientIp);

    /** 刷新：旧 refreshToken 用后即废（轮换）；旧 token 被再次使用视为重放，撤销该用户全部会话。 */
    LoginVO refresh(String refreshToken);

    /**
     * 登出。把当前 accessToken 的 {@code jti} 写进 Redis 黑名单（TTL = 剩余有效期），
     * 并撤销该用户的全部 refreshToken（模块 03）。
     *
     * @param accessToken 当前请求携带的 accessToken。**必须由调用方传入**：
     *                    吊销的对象是「签发出去的那一个 Token」，而 Spring Security 的上下文里
     *                    只留下了用户信息，没有 {@code jti} 与过期时间。
     */
    void logout(String accessToken);
}
