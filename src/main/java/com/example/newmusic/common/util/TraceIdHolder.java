package com.example.newmusic.common.util;

import cn.hutool.core.util.IdUtil;
import org.slf4j.MDC;

/**
 * TraceId 上下文：统一响应体与日志从这里取链路 ID。
 */
public final class TraceIdHolder {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIdHolder() {
    }

    /** 取当前线程 traceId，不存在则生成（不写回 MDC，避免污染异步线程）。 */
    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? generate() : traceId;
    }

    public static String generate() {
        return IdUtil.fastSimpleUUID().substring(0, 16);
    }
}
