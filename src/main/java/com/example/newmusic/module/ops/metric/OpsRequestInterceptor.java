package com.example.newmusic.module.ops.metric;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.common.util.TraceIdHolder;
import com.example.newmusic.module.ops.datasource.DataSourceContext;
import com.example.newmusic.module.ops.service.DegradeService;
import com.example.newmusic.module.ops.service.RateLimitRuleService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 统一的请求入口守卫：限流 → 降级 → 指标 → 链路采样。
 *
 * <p>顺序是有意为之：
 * <ol>
 *   <li>先清空 {@link DataSourceContext}，Tomcat 线程复用时不继承上一个请求的读写标记；</li>
 *   <li>限流放在最前，被限流的请求不再消耗业务资源（也就不该计入降级拦截）；</li>
 *   <li>降级守卫只做开关判断，不查业务表；</li>
 *   <li>指标与 trace 放在 {@code afterCompletion}，异常路径同样能记录到状态码与耗时。</li>
 * </ol>
 *
 * <p>OPTIONS 预检与 actuator/media/内部指标抓取不做限流/降级，避免监控与浏览器预检被误伤。
 */
@Component
public class OpsRequestInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = OpsRequestInterceptor.class.getName() + ".start";
    /** preHandle 已记录过指标/链路：afterCompletion 不再重复记一次。 */
    private static final String ATTR_RECORDED = OpsRequestInterceptor.class.getName() + ".recorded";

    private final RateLimitRuleService rateLimitRuleService;
    private final DegradeService degradeService;
    private final OpsMetricsService metricsService;
    private final OpsTraceBuffer traceBuffer;

    public OpsRequestInterceptor(RateLimitRuleService rateLimitRuleService,
                                 DegradeService degradeService,
                                 OpsMetricsService metricsService,
                                 OpsTraceBuffer traceBuffer) {
        this.rateLimitRuleService = rateLimitRuleService;
        this.degradeService = degradeService;
        this.metricsService = metricsService;
        this.traceBuffer = traceBuffer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DataSourceContext.clear();
        request.setAttribute(ATTR_START, System.currentTimeMillis());

        String path = request.getRequestURI();
        String method = request.getMethod();
        if (isPreflight(method) || isNoise(path)) {
            return true;
        }

        int level = degradeService.currentLevel();
        if (level > 0) {
            response.setHeader("X-Degrade-Level", String.valueOf(level));
        }

        try {
            rateLimitRuleService.check(request);
        } catch (BizException e) {
            metricsService.recordRateLimited();
            // preHandle 抛出后本拦截器的 afterCompletion 不会执行（Spring 只对返回 true 的
            // 拦截器回调完成阶段）。限流响应恰恰是要全量留痕的 429，所以在这里补记一次。
            recordRejected(request, 429);
            throw e;
        }
        try {
            degradeService.guard(method, path);
        } catch (BizException e) {
            metricsService.recordDegradeBlocked();
            // 降级拦截由异常处理器转成 HTTP 200 + 业务码 90005，按 200 记录请求量。
            recordRejected(request, HttpServletResponse.SC_OK);
            throw e;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String path = request.getRequestURI();
            String method = request.getMethod();
            if (isNoise(path) || Boolean.TRUE.equals(request.getAttribute(ATTR_RECORDED))) {
                return;
            }
            Object start = request.getAttribute(ATTR_START);
            long duration = start instanceof Long
                    ? Math.max(0, System.currentTimeMillis() - (Long) start) : 0L;
            int status = response.getStatus();
            metricsService.recordRequest(method, path, status, duration);

            if (traceBuffer.shouldRecord(status)) {
                String traceId = MDC.get(TraceIdHolder.MDC_KEY);
                if (!StringUtils.hasText(traceId)) {
                    traceId = TraceIdHolder.current();
                }
                traceBuffer.record(traceId, method, path, status, duration,
                        UserContext.getUserId(), IpUtil.getClientIp(request),
                        ex == null ? null : ex.toString());
            } else {
                traceBuffer.markDropped();
            }
        } finally {
            DataSourceContext.clear();
        }
    }

    /**
     * 在 preHandle 的拒绝路径上补齐指标与链路记录。
     *
     * <p>状态码用「拒绝语义」而不是当前 response 状态：此刻异常还没被处理器转换成响应，
     * 429 由 {@code Retry-After} 那条路径最终写入，这里按语义记 429 才能保证
     * 链路缓冲里「被限流的请求可被检索」（AC-28-008）。
     */
    private void recordRejected(HttpServletRequest request, int status) {
        request.setAttribute(ATTR_RECORDED, Boolean.TRUE);
        String path = request.getRequestURI();
        String method = request.getMethod();
        Object start = request.getAttribute(ATTR_START);
        long duration = start instanceof Long
                ? Math.max(0, System.currentTimeMillis() - (Long) start) : 0L;
        metricsService.recordRequest(method, path, status, duration);
        if (traceBuffer.shouldRecord(status)) {
            String traceId = MDC.get(TraceIdHolder.MDC_KEY);
            if (!StringUtils.hasText(traceId)) {
                traceId = TraceIdHolder.current();
            }
            traceBuffer.record(traceId, method, path, status, duration,
                    UserContext.getUserId(), IpUtil.getClientIp(request), null);
        } else {
            traceBuffer.markDropped();
        }
    }

    private static boolean isPreflight(String method) {
        return "OPTIONS".equalsIgnoreCase(method);
    }

    private static boolean isNoise(String path) {
        return path == null
                || path.equals("/error")
                || path.startsWith("/actuator")
                || path.startsWith("/media/")
                || path.startsWith("/ws")
                || path.equals("/api/v1/internal/ops/metrics");
    }
}
