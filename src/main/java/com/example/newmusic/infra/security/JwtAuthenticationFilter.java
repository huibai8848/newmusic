package com.example.newmusic.infra.security;

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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JWT 认证过滤器：解析 {@code Authorization: Bearer <accessToken>}，
 * 填充 {@link UserContext} 与 Spring Security 上下文。
 *
 * <p>Token 缺失/非法时**不直接失败**，按匿名放行，由 SecurityConfig 的授权规则决定是否 401——
 * 这样公开接口（曲库读）带过期 Token 也能正常访问。
 *
 * <p>模块 03 在这里补上两件 JWT 自己做不到的事（FR-12-004）：
 * <ul>
 *   <li><b>登出即时失效</b>：JWT 是无状态的，签发出去就收不回来。改法是登出时把该 Token 的
 *       {@code jti} 写进黑名单，这里逐请求核对；</li>
 *   <li><b>封禁即时生效</b>：登录时的状态校验拦不住「已经登录的人事后被封」，
 *       所以封禁标记必须在每个请求上核对。</li>
 * </ul>
 * 两者都只在 Redis 可用时生效，不可用时按 PRD EX-12-004-1 降级为「纯 JWT 校验」，即放行
 * （见 {@link TokenSessionStore}）。代价是每请求多 1~2 次 Redis 往返——本机实测单次亚毫秒，
 * 若日后成为瓶颈，把两次 GET 合成一次 pipeline 即可。
 *
 * <p>封禁与其它校验不同：它是**必须返回给用户的结果**，不能"降级放行"或"让它变成 401"。
 * 所以这里直接写响应体并中断过滤链，而不是清空上下文后继续。
 *
 * <p><b>已吊销的 Token 又是第三种情况</b>（系分 §5.4 / AC-12-004-3）：它既不能像非法 Token 那样
 * 静默降级成 40101，也不能像封禁那样无条件中断过滤链——带着「登出后残留的 Token」去读公开曲库
 * 应当按游客正常工作。所以这里只把原因记在请求属性上，由
 * {@link RestAuthEntryPoint} 在「确实需要鉴权」时才翻译成 40102。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性：Token 签名有效但已被吊销（登出）。供 AuthenticationEntryPoint 区分 40102 / 40101 */
    public static final String ATTR_TOKEN_REVOKED = "newmusic.tokenRevoked";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenSessionStore tokenSessionStore;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, TokenSessionStore tokenSessionStore,
                                   ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.tokenSessionStore = tokenSessionStore;
        this.objectMapper = objectMapper;
    }

    /**
     * C 端过滤器不接管后台请求。
     *
     * <p>本类是 {@code @Component}，可能被 Boot 自动注册到全局 Servlet 链。后台请求由
     * {@code AdminJwtAuthenticationFilter} 用另一套密钥认证；如果这里再按 C 端公钥解析一次，
     * 后台的 Bearer Token 必然解析失败，而 catch 分支会在 {@code chain.doFilter} 之前
     * 清空 {@link SecurityContextHolder} 与 {@link UserContext}，导致后台 Controller 看到的
     * 是「未登录」（40101）。Cookie 认证因为不带 Authorization 头而侥幸不受影响。
     *
     * <p>正确的结构是让安全过滤器只存在于各自的 SecurityFilterChain 中，见
     * {@link SecurityFilterRegistrationConfig}；这道判断是第二层保险。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                Claims claims = tokenProvider.parse(token);
                LoginUser loginUser = tokenProvider.toLoginUser(claims);
                if (loginUser != null && rejectIfBanned(loginUser, response, request)) {
                    return;
                }
                if (loginUser == null) {
                    // 解析不出用户信息：按匿名处理（与非法 Token 同等）
                } else if (tokenSessionStore.isAccessTokenRevoked(tokenProvider.getTokenId(claims))) {
                    // 登出过的 Token。**不在这里中断**：公开接口带残留 Token 仍应按游客访问，
                    // 只有真正需要鉴权的请求才在 AuthenticationEntryPoint 里变成 40102
                    request.setAttribute(ATTR_TOKEN_REVOKED, Boolean.TRUE);
                    log.debug("accessToken 已被吊销 uri={}", request.getRequestURI());
                } else {
                    authenticate(loginUser);
                }
            } catch (BizException e) {
                log.debug("Token 校验失败 code={} uri={}", e.getCode(), request.getRequestURI());
                SecurityContextHolder.clearContext();
                UserContext.clear();
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 封禁校验。返回 true 表示已写出响应、调用方应立即中断过滤链。
     */
    private boolean rejectIfBanned(LoginUser loginUser, HttpServletResponse response,
                                   HttpServletRequest request) throws IOException {
        if (!tokenSessionStore.isBanned(loginUser.getUserId())) {
            return false;
        }
        String reason = tokenSessionStore.banReason(loginUser.getUserId());
        log.warn("已封禁用户尝试访问 userId={} uri={}", loginUser.getUserId(), request.getRequestURI());
        // HTTP 200 + 业务码 20005：封禁是业务结果而不是传输层错误，
        // 让前端按统一响应体处理，不必为它开一条 401/403 的旁路
        response.setStatus(ResultCode.ACCOUNT_BANNED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                R.fail(ResultCode.ACCOUNT_BANNED, "账号已被封禁：" + (reason == null ? "违反社区规范" : reason)));
        return true;
    }

    private void authenticate(LoginUser loginUser) {
        List<SimpleGrantedAuthority> authorities = Stream
                .concat(nullSafe(loginUser.getRoles()).stream(), nullSafe(loginUser.getPerms()).stream())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserContext.set(loginUser);
    }

    private List<String> nullSafe(java.util.Set<String> set) {
        return set == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(set);
    }
}
