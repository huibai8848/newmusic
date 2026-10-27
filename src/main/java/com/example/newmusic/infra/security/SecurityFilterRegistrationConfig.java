package com.example.newmusic.infra.security;

import com.example.newmusic.module.admin.security.AdminCsrfFilter;
import com.example.newmusic.module.admin.security.AdminJwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阻止安全过滤器被 Servlet 容器二次注册。
 *
 * <p>这三个过滤器都是 {@code @Component} + {@code OncePerRequestFilter}。Spring Boot 会把
 * **所有** Filter Bean 自动注册进全局 Servlet 链（默认排在安全链之后），而它们本来只该属于
 * 各自的 {@code SecurityFilterChain}。多出来的那一次执行正好位于安全链之后、
 * {@code DispatcherServlet} 之前，于是出现了两类真实故障：
 *
 * <ul>
 *   <li>{@link AdminJwtAuthenticationFilter} 用后台密钥去解析 C 端每个 Bearer Token，
 *       解析失败直接写 401/40102 —— C 端登录成功后所有请求都被判「凭证无效」，
 *       刷新也救不回来，最终被踢回登录页；</li>
 *   <li>{@link JwtAuthenticationFilter} 在后台 Bearer 请求上解析失败并清空安全上下文，
 *       后台 Controller 拿到的是 40101（Cookie 认证因为不带 Authorization 头而不受影响）。</li>
 * </ul>
 *
 * <p>{@code setEnabled(false)} 只取消「Servlet 容器自动注册」这一条路；过滤器仍然通过
 * Security 链里的显式引用正常工作。各过滤器内的 {@code shouldNotFilter} 作为第二层保险保留。
 */
@Configuration
public class SecurityFilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> cEndJwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        return disabled(filter);
    }

    @Bean
    public FilterRegistrationBean<AdminJwtAuthenticationFilter> adminJwtFilterRegistration(
            AdminJwtAuthenticationFilter filter) {
        return disabled(filter);
    }

    @Bean
    public FilterRegistrationBean<AdminCsrfFilter> adminCsrfFilterRegistration(AdminCsrfFilter filter) {
        return disabled(filter);
    }

    private <T extends javax.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
