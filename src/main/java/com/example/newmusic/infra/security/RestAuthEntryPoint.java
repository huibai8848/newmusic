package com.example.newmusic.infra.security;

import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 未认证 → HTTP 401 + 统一响应体（《05-通用设计规范》1.4 例外条款），便于前端拦截器统一处理。
 *
 * <p>要区分两种「未认证」，因为前端的处置**完全不同**（系分 §5.4）：
 * <ul>
 *   <li>{@code 40101} 根本没带 / Token 不可解析 —— 前端可先尝试静默刷新；</li>
 *   <li>{@code 40102} Token 本身有效但已被登出吊销 —— 刷新也一定失败（登出连 refresh 一起作废），
 *       直接跳登录页即可，省掉一次注定失败的往返。</li>
 * </ul>
 * 判据由 {@link JwtAuthenticationFilter} 通过请求属性带过来：过滤器不便自行返回 401，
 * 因为它无法区分「这个接口要不要鉴权」——公开接口带残留 Token 应当照常按游客放行。
 */
@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ResultCode code = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_REVOKED))
                ? ResultCode.TOKEN_INVALID
                : ResultCode.UNAUTHORIZED;
        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(code));
    }
}
