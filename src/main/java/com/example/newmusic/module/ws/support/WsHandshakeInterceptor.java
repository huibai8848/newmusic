package com.example.newmusic.module.ws.support;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.security.JwtTokenProvider;
import com.example.newmusic.infra.security.TokenSessionStore;
import com.example.newmusic.module.ws.config.WsProperties;
import com.example.newmusic.module.ws.service.WsMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WS 握手鉴权（PRD 13 §4.3 / FR-13-001）。
 *
 * <p>浏览器原生 WebSocket 不能设置 {@code Authorization} 头，所以 token 走 query：
 * {@code ws://host/ws?token=xxx&deviceId=yyy}。代价是 token 会出现在访问日志里，
 * PRD 要求日志脱敏——这里**从不把完整 URL 写进日志**，只记 userId 与失败原因。
 *
 * <p>握手失败直接给 HTTP 状态码，不建立连接（PRD EX-13-001-1~5）：
 * 401 未登录/凭证无效、403 封禁、429 单 IP 超限、503 实例满。
 * 响应体仍用项目的统一错误码，方便排障时区分「凭证过期」和「根本没带 token」。
 *
 * <p><b>为什么不在 Spring Security 过滤器链里鉴权。</b>Security 只认
 * {@code Authorization: Bearer}，而浏览器 WS 带不了这个头。给 {@code /ws} 配一个
 * 自定义 query-token 过滤器也可以，但那条过滤器会同时作用于所有请求；
 * 握手拦截器是 WebSocket 生命周期的一部分，语义更窄、失败时也更容易给出正确的握手响应。
 */
@Slf4j
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "ws.userId";
    public static final String ATTR_NICKNAME = "ws.nickname";
    public static final String ATTR_DEVICE_ID = "ws.deviceId";
    public static final String ATTR_IP = "ws.ip";
    public static final String ATTR_TOKEN_EXPIRE_AT = "ws.tokenExpireAt";

    private static final String HEADER_AUTH = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final int MAX_DEVICE_ID_LENGTH = 64;

    private final JwtTokenProvider tokenProvider;
    private final TokenSessionStore tokenSessionStore;
    private final WsSessionRegistry registry;
    private final WsProperties properties;
    private final WsMetrics metrics;
    private final ObjectMapper objectMapper;

    public WsHandshakeInterceptor(JwtTokenProvider tokenProvider,
                                  TokenSessionStore tokenSessionStore,
                                  WsSessionRegistry registry,
                                  WsProperties properties,
                                  WsMetrics metrics,
                                  ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.tokenSessionStore = tokenSessionStore;
        this.registry = registry;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes)
            throws IOException {
        String ip = clientIp(request);
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            return reject(response, ip, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED,
                    "缺少 token");
        }

        LoginUser user;
        long tokenExpireAt;
        try {
            Claims claims = tokenProvider.parse(token);
            user = tokenProvider.toLoginUser(claims);
            if (user == null) {
                return reject(response, ip, HttpStatus.UNAUTHORIZED, ResultCode.TOKEN_INVALID,
                        "凭证类型不正确");
            }
            tokenExpireAt = claims.getExpiration() == null ? 0L : claims.getExpiration().getTime();
        } catch (BizException e) {
            return reject(response, ip, HttpStatus.UNAUTHORIZED, e.getCode() == 40103
                    ? ResultCode.TOKEN_EXPIRED : ResultCode.TOKEN_INVALID, e.getMessage());
        }

        if (tokenSessionStore.isBanned(user.getUserId())) {
            return reject(response, ip, HttpStatus.FORBIDDEN, ResultCode.ACCOUNT_BANNED,
                    "账号已被封禁：" + nullSafe(tokenSessionStore.banReason(user.getUserId())));
        }
        if (registry.totalConnections() >= properties.getMaxConnections()) {
            return reject(response, ip, HttpStatus.SERVICE_UNAVAILABLE, ResultCode.SYSTEM_BUSY,
                    "服务连接数已达上限");
        }
        if (registry.countByIp(ip) >= properties.getMaxConnectionsPerIp()) {
            return reject(response, ip, HttpStatus.TOO_MANY_REQUESTS, ResultCode.RATE_LIMITED,
                    "当前 IP 连接数过多");
        }

        attributes.put(ATTR_USER_ID, user.getUserId());
        attributes.put(ATTR_NICKNAME, user.getNickname() == null ? user.getUsername() : user.getNickname());
        attributes.put(ATTR_DEVICE_ID, deviceId(request));
        attributes.put(ATTR_IP, ip);
        attributes.put(ATTR_TOKEN_EXPIRE_AT, tokenExpireAt);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无状态，无需清理
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 从 query 取 token，其次接受 {@code Authorization: Bearer}。
     *
     * <p>后者不是给浏览器用的，而是给服务间/压测客户端：它们能自定义头，
     * 也就没必要把 token 暴露在 URL 里。
     */
    private String extractToken(ServerHttpRequest request) {
        String queryToken = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        String header = request.getHeaders().getFirst(HEADER_AUTH);
        if (StringUtils.hasText(header) && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        return null;
    }

    private String deviceId(ServerHttpRequest request) {
        String raw = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("deviceId");
        if (!StringUtils.hasText(raw)) {
            return "default";
        }
        String sanitized = raw.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
        return sanitized.length() > MAX_DEVICE_ID_LENGTH
                ? sanitized.substring(0, MAX_DEVICE_ID_LENGTH) : sanitized;
    }

    private String clientIp(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest) {
            return IpUtil.getClientIp(((ServletServerHttpRequest) request).getServletRequest());
        }
        return request.getRemoteAddress() == null ? "unknown"
                : request.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean reject(ServerHttpResponse response, String ip, HttpStatus status,
                           ResultCode code, String message) throws IOException {
        metrics.incrementHandshakeFail();
        // 不打印 URI：它带着 token（PRD §4.3 要求日志脱敏）
        log.info("WS 握手被拒 ip={} status={} code={} reason={}", ip, status.value(),
                code.getCode(), message);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.getCode());
        body.put("message", message);
        response.getBody().write(objectMapper.writeValueAsString(body)
                .getBytes(StandardCharsets.UTF_8));
        return false;
    }

    private static String nullSafe(String value) {
        return value == null ? "违反社区规范" : value;
    }
}
