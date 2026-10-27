package com.example.newmusic.module.admin.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 后台 Token 的 Cookie 读写。access 与 refresh 用不同 Path，缩小暴露面：
 * refresh 只在 {@code /api/v1/admin/auth} 下发送，业务接口拿不到它。
 */
@Component
public class AdminCookieSupport {

    private final AdminJwtProperties properties;

    public AdminCookieSupport(AdminJwtProperties properties) {
        this.properties = properties;
    }

    public void writeTokens(HttpServletResponse response, String accessToken, String refreshToken) {
        add(response, properties.getAccessCookie(), accessToken, "/api/v1/admin",
                properties.getAccessTokenTtl());
        add(response, properties.getRefreshCookie(), refreshToken, "/api/v1/admin/auth",
                properties.getRefreshTokenTtl());
    }

    public void clear(HttpServletResponse response) {
        add(response, properties.getAccessCookie(), "", "/api/v1/admin", 0);
        add(response, properties.getRefreshCookie(), "", "/api/v1/admin/auth", 0);
    }

    public String readRefresh(HttpServletRequest request) {
        return read(request, properties.getRefreshCookie());
    }

    public String readAccess(HttpServletRequest request) {
        return read(request, properties.getAccessCookie());
    }

    private String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void add(HttpServletResponse response, String name, String value,
                     String path, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
