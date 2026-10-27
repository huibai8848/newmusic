package com.example.newmusic.module.admin.security;

import com.example.newmusic.infra.security.RestAccessDeniedHandler;
import com.example.newmusic.infra.security.RestAuthEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 后台独立过滤链。{@code @Order(1)} + {@code securityMatcher} 保证它只接管
 * {@code /api/v1/admin/**}，C 端链（SecurityConfig，@Order(2)）完全不知道后台存在。
 *
 * <p>Cookie 认证 + 无状态：没有 HttpSession；refresh 轮换账本在 Redis。
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                AdminJwtAuthenticationFilter authFilter,
                                                AdminCsrfFilter csrfFilter,
                                                RestAuthEntryPoint authEntryPoint,
                                                RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        // Spring Security 5.3 没有 securityMatcher(String)，用 requestMatcher 限定作用域
        http.requestMatcher(new AntPathRequestMatcher("/api/v1/admin/**"))
                .csrf().disable()
                .cors().and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/admin/auth/captcha").permitAll()
                .antMatchers(HttpMethod.POST,
                        "/api/v1/admin/auth/login",
                        "/api/v1/admin/auth/refresh").permitAll()
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
                .and()
                .addFilterBefore(csrfFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .headers().frameOptions().sameOrigin();
        return http.build();
    }
}
