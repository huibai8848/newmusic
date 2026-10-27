package com.example.newmusic.module.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 后台认证过滤器的路径边界（本次浏览器走查暴露的「旧 Cookie 导致无法重新登录」）。
 *
 * <p>过滤器是 {@code @Component}，会被 Spring Boot 自动注册到全局 Servlet 过滤链，
 * 所以不能只靠 SecurityConfig 的 matcher 限定范围。这里直接测 {@code shouldNotFilter}：
 * 登录前的 captcha/login、以及登出必须绕过；其它后台接口仍要校验。
 *
 * <p>不覆盖 Token 解析本身（那是 {@code AdminJwtTokenProvider} 的职责），
 * 也不覆盖 Redis 会话版本（需要真实 Redis，见 {@code RedisCompatibilityTest}）。
 */
@DisplayName("AdminJwtAuthenticationFilter 路径放行")
class AdminJwtAuthenticationFilterTest {

    private AdminJwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminJwtAuthenticationFilter(
                mock(AdminJwtTokenProvider.class),
                mock(AdminSessionStore.class),
                new AdminJwtProperties(),
                new ObjectMapper());
    }

    @Test
    @DisplayName("登录前端点绕过旧 Cookie 校验")
    void publicAuthEndpointsSkipFilter() {
        assertTrue(shouldNotFilter("/api/v1/admin/auth/captcha"));
        assertTrue(shouldNotFilter("/api/v1/admin/auth/login"));
        assertTrue(shouldNotFilter("/api/v1/admin/auth/refresh"));
    }

    @Test
    @DisplayName("需要登录的后台接口仍然校验（含 logout）")
    void protectedAdminEndpointsAreFiltered() {
        assertFalse(shouldNotFilter("/api/v1/admin/songs"));
        assertFalse(shouldNotFilter("/api/v1/admin/audits"));
        assertFalse(shouldNotFilter("/api/v1/admin/auth/me"));
        // logout 需要过滤器建立 SecurityContext，否则会被 Security 判成 40101
        assertFalse(shouldNotFilter("/api/v1/admin/auth/logout"));
        // 前缀相同但不是登录端点的新路径不能被误放行
        assertFalse(shouldNotFilter("/api/v1/admin/auth/login-anything"));
    }

    @Test
    @DisplayName("非后台路径完全不接管（C 端 Token 不能进后台过滤器）")
    void nonAdminPathsSkipFilter() {
        assertTrue(shouldNotFilter("/api/v1/music/songs"));
        assertTrue(shouldNotFilter("/api/v1/player/url"));
        assertTrue(shouldNotFilter("/doc.html"));
    }

    @Test
    @DisplayName("部署到 context-path 后仍能识别登录端点（requestURI 带前缀）")
    void contextPathIsStrippedBeforeMatching() {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/newmusic/api/v1/admin/auth/login");
        login.setContextPath("/newmusic");
        assertTrue(filter.shouldNotFilter(login));

        MockHttpServletRequest protectedApi = new MockHttpServletRequest("GET", "/newmusic/api/v1/admin/songs");
        protectedApi.setContextPath("/newmusic");
        assertFalse(filter.shouldNotFilter(protectedApi));
    }

    @Test
    @DisplayName("尾斜杠不会让登录端点漏网（/auth/login/ 也要放行）")
    void trailingSlashIsNormalized() {
        assertTrue(shouldNotFilter("/api/v1/admin/auth/login/"));
        assertTrue(shouldNotFilter("/api/v1/admin/auth/captcha//"));
        // 规范化只去尾斜杠，不做前缀通配
        assertFalse(shouldNotFilter("/api/v1/admin/auth/logout/"));
        assertFalse(shouldNotFilter("/api/v1/admin/auth/login-anything/"));
    }

    private boolean shouldNotFilter(String uri) {
        return filter.shouldNotFilter(new MockHttpServletRequest("GET", uri));
    }
}
