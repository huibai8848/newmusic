package com.example.newmusic.common.web;

import com.example.newmusic.common.util.TraceIdHolder;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * TraceId 过滤器：上游透传 {@code X-Trace-Id}，否则生成；写入 MDC 与响应头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    private static final int MAX_LEN = 64;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest) {
            traceId = ((HttpServletRequest) request).getHeader(TraceIdHolder.HEADER);
        }
        if (!StringUtils.hasText(traceId) || traceId.length() > MAX_LEN || !isSafe(traceId)) {
            traceId = TraceIdHolder.generate();
        }
        MDC.put(TraceIdHolder.MDC_KEY, traceId);
        if (response instanceof HttpServletResponse) {
            ((HttpServletResponse) response).setHeader(TraceIdHolder.HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIdHolder.MDC_KEY);
        }
    }

    /** 拒绝把任意用户输入直接写进日志/响应头。 */
    private boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
