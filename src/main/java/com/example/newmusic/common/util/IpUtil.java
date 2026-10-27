package com.example.newmusic.common.util;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 提取（兼容 Nginx 反代）。
 */
public final class IpUtil {

    private static final String[] HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
    };

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int idx = value.indexOf(',');
                return idx > 0 ? value.substring(0, idx).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
