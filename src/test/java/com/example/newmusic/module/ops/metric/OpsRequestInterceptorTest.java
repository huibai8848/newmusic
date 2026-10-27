package com.example.newmusic.module.ops.metric;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.service.DegradeService;
import com.example.newmusic.module.ops.service.RateLimitRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求入口守卫的拒绝路径单测（FR-28-008 / AC-28-008）。
 *
 * <p>Spring 只对 {@code preHandle} 返回 true 的拦截器回调 {@code afterCompletion}。
 * 限流在 {@code preHandle} 里抛异常，如果不在抛之前补记，**被限流的 429 请求永远不会进入
 * 链路缓冲**——而 429 恰恰是排障时最想看的请求。这个测试锁住「补记一次且只记一次」。
 */
@DisplayName("OpsRequestInterceptor 拒绝路径留痕")
class OpsRequestInterceptorTest {

    private RateLimitRuleService rateLimitRuleService;
    private DegradeService degradeService;
    private OpsMetricsService metricsService;
    private OpsTraceBuffer traceBuffer;
    private OpsRequestInterceptor interceptor;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        rateLimitRuleService = mock(RateLimitRuleService.class);
        degradeService = mock(DegradeService.class);
        metricsService = mock(OpsMetricsService.class);
        traceBuffer = mock(OpsTraceBuffer.class);
        when(traceBuffer.shouldRecord(429)).thenReturn(true);
        interceptor = new OpsRequestInterceptor(rateLimitRuleService, degradeService,
                metricsService, traceBuffer);
        response = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("限流拒绝时按 429 记录指标与链路，且 afterCompletion 不重复记录")
    void recordsRateLimitedRequestOnce() {
        // check/guard 是 void 方法，只能走 doThrow 形式
        doThrow(new BizException(ResultCode.RATE_LIMITED, "请求过于频繁"))
                .when(rateLimitRuleService).check(any());
        HttpServletRequest request = requestWithAttributes();

        assertThrows(BizException.class, () -> interceptor.preHandle(request, response, new Object()));

        verify(metricsService).recordRateLimited();
        verify(metricsService, times(1)).recordRequest(
                eq("GET"), eq("/api/v1/search/hot"), eq(429), anyLong());
        verify(traceBuffer).record(nullable(String.class), eq("GET"), eq("/api/v1/search/hot"),
                eq(429), anyLong(), isNull(), eq("1.1.1.1"), isNull());

        // 拦截器抛出后容器仍可能走完成阶段；标记位必须阻止第二次计数
        interceptor.afterCompletion(request, response, new Object(), null);
        verify(metricsService, times(1)).recordRequest(
                anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("降级拦截按 HTTP 200 记录（90005 走的是 200 响应）")
    void recordsDegradeBlockedAsOk() {
        doThrow(new BizException(ResultCode.SERVICE_DEGRADED, "降级中"))
                .when(degradeService).guard(anyString(), anyString());
        HttpServletRequest request = requestWithAttributes();

        assertThrows(BizException.class, () -> interceptor.preHandle(request, response, new Object()));

        verify(metricsService).recordDegradeBlocked();
        verify(metricsService).recordRequest(eq("GET"), eq("/api/v1/search/hot"), eq(200), anyLong());
        // 200 正常响应受采样率控制，不应强行入库
        verify(traceBuffer).markDropped();
    }

    private HttpServletRequest requestWithAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/search/hot");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1");
        when(request.getRemoteAddr()).thenReturn("1.1.1.1");
        doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return request;
    }
}
