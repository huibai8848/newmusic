package com.example.newmusic.module.admin.security;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 后台认证过滤器。只挂在 {@code /api/v1/admin/**} 的过滤链上（见 AdminSecurityConfig），
 * 因此不会与 C 端过滤器重复解析。
 *
 * <p>Token 来源优先级：{@code Authorization: Bearer}（验收脚本/内部调用）→ httpOnly Cookie
 * （浏览器）。两者签名与 issuer 都是后台专用，C 端 Token 到这里一定解析失败。
 */
@Slf4j
@Component
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final AdminJwtTokenProvider tokenProvider;
    private final AdminSessionStore sessionStore;
    private final AdminJwtProperties properties;
    private final ObjectMapper objectMapper;

    public AdminJwtAuthenticationFilter(AdminJwtTokenProvider tokenProvider,
                                        AdminSessionStore sessionStore,
                                        AdminJwtProperties properties,
                                        ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 只接管后台路径。
     *
     * <p>本类是 {@code @Component}，Spring Boot 会把它自动注册进全局 Servlet 过滤链（不只在
     * {@link AdminSecurityConfig} 里那一条）。没有路径限定时，它会用**后台**密钥去解析 C 端
     * 每个带 {@code Authorization: Bearer} 的请求：C 端 Token 必然解析失败，于是直接写出
     * 401/40102 并中断过滤链。表现是 C 端登录成功后，紧接着的所有请求都被判「登录凭证无效」，
     * 前端刷新一次仍然失败，最终被踢回登录页。
     *
     * <p>匿名请求不带 Authorization 头，所以这个问题只在登录后暴露——不要删掉这道判断。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = pathWithinApplication(request);
        if (!uri.startsWith("/api/v1/admin/")) {
            return true;
        }
        // 登录前必须绕过「旧 Cookie 校验」。
        // 浏览器会无条件带上过期或被安全策略终止的旧 Cookie；如果这里先写 40102，
        // 连 /auth/captcha、/auth/login 一起被挡住，用户看到「请重新登录」却永远登不进去。
        //
        // 注意不能把 /auth/logout 一起放开：它在 SecurityConfig 里要求已认证，
        // 跳过本过滤器后 SecurityContext 为空，会变成 40101，退出登录反而失败。
        // 旧 Cookie 场景下不需要 logout——重新登录会覆盖两个 Cookie。
        return uri.equals("/api/v1/admin/auth/captcha")
                || uri.equals("/api/v1/admin/auth/login")
                || uri.equals("/api/v1/admin/auth/refresh");
    }

    /**
     * 取「应用内路径」并去掉尾部斜杠。
     *
     * <p>不能直接用 `requestURI`：
     * <ul>
     *   <li>部署到 `server.servlet.context-path` 后，requestURI 会变成
     *       `/newmusic/api/v1/admin/...`，上面三个精确比较全部失效，旧 Cookie 死锁回归；</li>
     *   <li>前端多打一个尾斜杠（`/auth/login/`）同样匹配不上。</li>
     * </ul>
     * 这里只做规范化，不做通配：否则 `/auth/login-anything` 也会被误放行。
     */
    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && StringUtils.hasText(uri) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        int end = uri.length();
        while (end > 1 && uri.charAt(end - 1) == '/') {
            end--;
        }
        return uri.substring(0, end);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = tokenProvider.parse(token);
                LoginUser admin = tokenProvider.toLoginUser(claims);
                if (admin == null) {
                    writeError(response, ResultCode.TOKEN_INVALID);
                    return;
                }
                if (sessionStore.isAccessRevoked(tokenProvider.getTokenId(claims))) {
                    writeError(response, ResultCode.TOKEN_INVALID, "登录凭证已失效，请重新登录");
                    return;
                }
                long currentVersion = sessionStore.currentVersion(admin.getUserId());
                if (tokenProvider.getSessionVersion(claims) < currentVersion) {
                    writeError(response, ResultCode.TOKEN_INVALID, "登录状态已被管理员安全策略终止，请重新登录");
                    return;
                }
                authenticate(admin);
            } catch (BizException e) {
                writeError(response, ResultCode.TOKEN_INVALID,
                        e.getCode() == ResultCode.TOKEN_EXPIRED.getCode()
                                ? "登录已过期，请重新登录" : "登录凭证无效，请重新登录");
                return;
            } catch (Exception e) {
                // Redis 不可用时 currentVersion 会抛 90003；后台按 fail-closed 处理
                log.error("后台认证检查失败 uri={}", request.getRequestURI(), e);
                writeError(response, ResultCode.CACHE_ERROR, "登录状态校验失败，请稍后重试");
                return;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (properties.getAccessCookie().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void authenticate(LoginUser admin) {
        List<SimpleGrantedAuthority> authorities = Stream
                .concat(admin.getRoles() == null ? new ArrayList<String>().stream() : admin.getRoles().stream(),
                        admin.getPerms() == null ? new ArrayList<String>().stream() : admin.getPerms().stream())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(admin, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserContext.set(admin);
    }

    private void writeError(HttpServletResponse response, ResultCode code) throws IOException {
        writeError(response, code, code.getMessage());
    }

    private void writeError(HttpServletResponse response, ResultCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(code, message));
    }
}
