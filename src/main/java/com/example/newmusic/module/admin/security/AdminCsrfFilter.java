package com.example.newmusic.module.admin.security;

import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 后台 CSRF 防线。
 *
 * <p>后台 Token 放在 httpOnly Cookie 里（PRD FR-18-001），因此不能像 C 端那样
 * 直接关掉 CSRF 了事。这里采用「Origin / Referer 白名单」而不是 CSRF Token：
 * 后台是纯前后端分离应用，所有写请求都由同源的 admin SPA 发起，浏览器会带上
 * Origin；而脚本/内部调用没有 Origin，按非浏览器客户端放行（它们也无法利用
 * 受害者的 Cookie）。
 *
 * <p>只校验非安全方法；GET/HEAD/OPTIONS 不受影响。
 */
@Component
public class AdminCsrfFilter extends OncePerRequestFilter {

    private final AdminJwtProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedOrigins;

    public AdminCsrfFilter(AdminJwtProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.allowedOrigins = new LinkedHashSet<>();
        Arrays.stream(properties.getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(allowedOrigins::add);
    }

    /**
     * 只接管后台路径。
     *
     * <p>本类是 {@code @Component}，Spring Boot 会把它自动注册进全局 Servlet 过滤链（不只在
     * {@link AdminSecurityConfig} 里那一条）。如果不加路径判断，后台的 5174 白名单会误杀
     * C 端 5173 的所有写请求——浏览器对同源 POST 同样会带 {@code Origin}。
     * C 端跨域由 {@code WebMvcConfig} 的 CORS 配置负责，不需要这层 CSRF 校验。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isUnsafe(request.getMethod())) {
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");
            String candidate = StringUtils.hasText(origin) ? origin : referer;
            if (StringUtils.hasText(candidate) && !isAllowed(candidate)) {
                response.setStatus(ResultCode.NO_PERMISSION.getHttpStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(),
                        R.fail(ResultCode.NO_PERMISSION, "请求来源不被信任"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isUnsafe(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }

    private boolean isAllowed(String value) {
        try {
            URI uri = URI.create(value);
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            return allowedOrigins.contains(origin);
        } catch (Exception e) {
            return false;
        }
    }
}
