package com.example.newmusic.module.search.support;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.infra.ratelimit.RateLimitService;
import com.example.newmusic.module.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 搜索接口的两档限流（BR-15-008：游客 30/分钟，登录 60/分钟，系分 D-39）。
 *
 * <p><b>为什么不能用一个 {@code @RateLimit} 注解搞定。</b>注解是「一个注解 = 一个阈值」，
 * 而这里同一个接口要按登录态给两档。三条候选路：
 * <ol>
 *   <li>给注解加 {@code limitAuthenticated} 属性 + 新维度——要动模块 03 的公共组件，
 *       回归面覆盖所有已标注接口；</li>
 *   <li>叠两个注解（IP 30 + USER 60）——<b>错的</b>：登录用户也有 IP，
 *       会先被 30 那档拦住，「登录后额度翻倍」这个需求根本不成立；</li>
 *   <li>直接调用 {@link RateLimitService#enforce}——它本来就是 public 的
 *       （ACCOUNT 维度的登录流程已经在这么用）。</li>
 * </ol>
 * 选 ③：**零改动模块 03**，语义也是对的——登录用户查 USER 档、游客查 IP 档，
 * 两者的限流键天然分片（维度段不同），不会互相污染计数。
 *
 * <p><b>两档并存是可验证的</b>：同一个 IP，未登录打第 31 次被拒；带上 token 再打，
 * 因为换了 USER 维度，第 31 次仍然放行，直到第 61 次才被拒（AC 的 §08）。
 */
@Slf4j
@Component
public class SearchRateLimiter {

    /** 限流键的 type 段。同一 type + 不同 dimension 就是两个独立的计数器 */
    private static final String TYPE = "search";
    private static final int WINDOW_SECONDS = 60;

    private final RateLimitService rateLimitService;
    private final SearchProperties properties;

    public SearchRateLimiter(RateLimitService rateLimitService, SearchProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * 判定并拒绝。
     *
     * <p>豁免规则与 {@code RateLimitAspect} 保持一致（白名单 IP、超管）——
     * 两处各写一套的话，必然出现「注解限的接口超管能过、显式限的接口超管被拦」这种
     * 只会在排障时才发现的不一致。
     */
    public void enforce() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非 HTTP 上下文（单测直调、内部调用）：无限流维度可言
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        if (rateLimitService.isWhitelisted(IpUtil.getClientIp(request)) || isSuperAdmin()) {
            return;
        }

        LoginUser loginUser = UserContext.get();
        String api = request.getMethod() + ':' + request.getRequestURI();
        if (loginUser != null) {
            rateLimitService.enforce(TYPE, RateLimitDimension.USER, String.valueOf(loginUser.getUserId()),
                    properties.getLimit().getMemberPerMinute(), WINDOW_SECONDS, api, null);
        } else {
            rateLimitService.enforce(TYPE, RateLimitDimension.IP, IpUtil.getClientIp(request),
                    properties.getLimit().getGuestPerMinute(), WINDOW_SECONDS, api, null);
        }
    }

    private boolean isSuperAdmin() {
        LoginUser loginUser = UserContext.get();
        return loginUser != null && loginUser.isSuperAdmin();
    }
}
