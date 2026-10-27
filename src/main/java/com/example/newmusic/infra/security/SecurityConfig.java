package com.example.newmusic.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：无状态 JWT 鉴权 + 权限码校验。
 *
 * <p>授权规则（《02-用户角色与权限矩阵》第 4 节）：
 * 游客可读公开内容（曲库/搜索），写操作与个人资源需要登录，后台接口另需权限码（{@code @PreAuthorize}）。
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /** 无需认证的路径前缀 */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            // 模块 13：时钟同步要轻量、可被游客反复调用（NTP 采样），返回里只有时间戳
            "/api/v1/time",
            // 音频/封面静态资源（模块 07）：真实防盗链在 CDN / Nginx 层，本地静态目录不做校验
            "/media/**",
            "/doc.html", "/webjars/**", "/swagger-resources/**", "/v2/api-docs", "/v3/api-docs/**",
            "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico", "/error",
            // 模块19：存活/就绪探针要能被 K8s/kubelet 匿名访问（含 /health/liveness、/health/readiness）。
            // 管理端口已绑定 127.0.0.1（management.server.address），Prometheus 抓取也只从本机发起；
            // 跨机抓取请走 /api/v1/internal/ops/metrics（有 IP 白名单），不要把本机指到 0.0.0.0。
            "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
            // WebSocket 握手（模块 04）：浏览器原生 WebSocket 不能自定义 Authorization 头，
            // token 只能走 query 参数。握手鉴权在 WsHandshakeInterceptor 里完成（失败返回
            // 401/403/429/503），这里放行是为了让请求到达握手拦截器，而不是被 Security
            // 用「未携带 Bearer」先挡成 401。**不要**把 /ws 当成匿名接口——它只是换了一道门。
            "/ws", "/ws/**",
            // 分享短链跳转（模块 10 FR-19-007）：公开，未登录也要能打开
            "/s/**",
            // IK 远程词典（模块 11 FR-20-002）：ES 直接拉取，没有 JWT。
            // 这里放行只代表「不要求登录」，来源白名单在 InternalDictController 里
            // 用 getRemoteAddr() 校验——绝不能把它当作公开词典接口。
            "/api/v1/internal/dict/**",
            // 模块19：C 端降级状态必须匿名可读（降级时用户还没登录也要看到公告）
            "/api/v1/system/**",
            // Prometheus 抓取没有 JWT；InternalOpsController 用 IP 白名单校验来源
            "/api/v1/internal/ops/**"
    };

    /**
     * C 端过滤链。{@code @Order(2)} 让后台链（@Order(1)，securityMatcher=/api/v1/admin/**）
     * 先匹配；后台请求不会落到这条链上。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           RestAuthEntryPoint authEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf().disable()
                .cors().and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers(PUBLIC_PATHS).permitAll()
                // 曲库读接口对游客开放；写接口走下面的 authenticated + @PreAuthorize
                .antMatchers(HttpMethod.GET, "/api/v1/music/**").permitAll()
                // 模块 14：已生成的歌词翻译与情绪标签是公开内容（和歌词本身同级）。
                // 只放行 GET；生成翻译 POST /feedback 都必须登录。
                .antMatchers(HttpMethod.GET, "/api/v1/lyric/**", "/api/v1/songs/*/emotion").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/rooms/**").permitAll()
                // 播放地址签发：游客可调（试听限制在模块 03/12 用 Redis 实现，不在鉴权层做）
                .antMatchers(HttpMethod.GET, "/api/v1/player/**").permitAll()
                // 模块 12：可用音质列表是「这首歌唱不唱歌」的公开元数据；设置/进度/设备仍需登录
                .antMatchers(HttpMethod.GET, "/api/v1/play/qualities").permitAll()
                // 播放开始/结束上报对游客开放（BR-16-032：游客历史落 deviceId）；
                // progress/history 是个人数据，仍走下面的 authenticated。
                .antMatchers(HttpMethod.POST, "/api/v1/play/record", "/api/v1/play/end").permitAll()
                // 相似歌曲是「基于当前歌曲」的公共推荐（PRD 17 FR-17-004，使用角色：全部）
                .antMatchers(HttpMethod.GET, "/api/v1/recommend/similar").permitAll()
                // 模块 15：推荐流与埋点对游客开放（FR-24-001 冷启动 / EX-24-001-2）。
                // 游客主体是 deviceId，实验分流也支持 deviceId；个人数据类接口仍需登录。
                .antMatchers(HttpMethod.GET, "/api/v1/recommend/feed").permitAll()
                .antMatchers(HttpMethod.POST,
                        "/api/v1/recommend/feed",
                        "/api/v1/recommend/events",
                        "/api/v1/recommend/events/batch").permitAll()
                // 搜索（模块 06）：检索/联想/热搜都是公开读接口（FR-15-001，游客 30 次/分钟
                // 由 SearchRateLimiter 按 IP 限流，不在鉴权层做）。
                // **逐个列出而不是 /api/v1/search/** 一把放行**：/api/v1/search/history 是
                // 「我搜过什么」这种个人数据，它必须留在下面的 authenticated 里。用通配符的话
                // 它会被一并放开，只靠 Controller 内那句 UserContext.requireUserId() 挡着——
                // 那道防线仍然保留（纵深防御），但不该是唯一的一道。
                .antMatchers(HttpMethod.GET,
                        "/api/v1/search",
                        "/api/v1/search/songs",
                        "/api/v1/search/artists",
                        "/api/v1/search/albums",
                        "/api/v1/search/playlists",
                        "/api/v1/search/suggest",
                        "/api/v1/search/hot").permitAll()
                // 社交（模块 10）：评论列表/回复列表与举报理由字典是公开读；分享短链公开可建。
                // 评论列表仍会读 UserContext.getUserId()（有 token 时返回「我点过赞」），
                // 但鉴权层不强制登录。
                .antMatchers(HttpMethod.GET,
                        "/api/v1/comments",
                        "/api/v1/comments/*/replies",
                        "/api/v1/reports/reasons").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/share").permitAll()
                // 模块 17：埋点采集匿名开放（游客也要上报 page_view / player_*）。
                // JWT 过滤器仍会执行：带 token 时补 user_id，不带 token 时按 deviceId 归因；
                // 限流、去重、敏感字段清洗都在 TrackIngestService 里做。
                .antMatchers(HttpMethod.POST,
                        "/api/v1/track/events",
                        "/api/v1/track/batch").permitAll()
                // 模块 16：公开主页、动态详情、话题与粉丝榜是公开读（服务层已按隐私/可见性裁剪，
                // 且都只读 UserContext.getUserId()，游客为 null）；互动、私信、推荐、@ 搜索仍需登录。
                .antMatchers(HttpMethod.GET,
                        "/api/v1/posts/*",
                        "/api/v1/posts/*/comments",
                        "/api/v1/users/*/profile",
                        "/api/v1/users/*/stats",
                        "/api/v1/users/*/recent-listening",
                        "/api/v1/users/*/playlists",
                        "/api/v1/users/*/about",
                        "/api/v1/users/*/posts",
                        "/api/v1/users/*/fans-rank",
                        "/api/v1/topics/hot",
                        "/api/v1/topics/search",
                        "/api/v1/topics/*/posts").permitAll()
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
                .and()
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers().frameOptions().sameOrigin();

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
