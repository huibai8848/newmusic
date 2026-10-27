package com.example.newmusic.module.ops.metric;

import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.service.OpsConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链路环形缓冲单测（FR-28-008 / AC-28-008-1~6）。
 *
 * <p>缓冲容量有硬上限，满了必须覆盖最旧记录而不是无限增长；5xx/429 全量保留，
 * 2xx 才受采样率影响。检索条件是 AND，traceId 用前缀匹配，其余用包含/精确匹配。
 */
@DisplayName("OpsTraceBuffer 链路缓冲")
class OpsTraceBufferTest {

    private OpsConfigService configService;
    private OpsTraceBuffer buffer;

    @BeforeEach
    void setUp() {
        OpsProperties properties = new OpsProperties();
        properties.setTraceBufferSize(100);
        properties.setTraceSampleRate(0.1d);
        configService = mock(OpsConfigService.class);
        when(configService.getDouble(anyString(), anyDouble())).thenReturn(0.1d);
        buffer = new OpsTraceBuffer(properties, configService);
    }

    @Test
    @DisplayName("5xx/4xx 全量记录，2xx 受采样率控制")
    void samplingKeepsErrors() {
        assertTrue(buffer.shouldRecord(500));
        assertTrue(buffer.shouldRecord(429));
        assertTrue(buffer.shouldRecord(404));

        when(configService.getDouble(anyString(), anyDouble())).thenReturn(0d);
        assertFalse(buffer.shouldRecord(200));

        when(configService.getDouble(anyString(), anyDouble())).thenReturn(1d);
        assertTrue(buffer.shouldRecord(200));
    }

    @Test
    @DisplayName("空白 traceId 不入缓冲")
    void ignoresBlankTraceId() {
        buffer.record("", "GET", "/api/v1/songs", 200, 10L, null, null, null);
        buffer.record(null, "GET", "/api/v1/songs", 200, 10L, null, null, null);

        assertEquals(0L, buffer.stats().get("recorded"));
        assertTrue(buffer.search(null, null, null, null, null, 50).isEmpty());
    }

    @Test
    @DisplayName("超长字段按上限截断，避免一条记录撑爆缓冲")
    void truncatesLongFields() {
        String longTrace = repeat("x", 100);
        String longPath = repeat("p", 400);
        String longError = repeat("e", 400);

        buffer.record(longTrace, "POST", longPath, 500, 1L, 7L, "1.1.1.1", longError);

        List<OpsTraceBuffer.TraceRecord> result = buffer.search(repeat("x", 64), null, null, null, null, 10);
        assertEquals(1, result.size());
        assertEquals(64, result.get(0).getTraceId().length());
        assertEquals(300, result.get(0).getPath().length());
        assertEquals(300, result.get(0).getError().length());
    }

    @Test
    @DisplayName("检索条件按 AND 组合，traceId 前缀忽略大小写")
    void searchesWithAndFilters() {
        buffer.record("AbC123", "GET", "/api/v1/search/hot", 200, 50L, 7L, "1.1.1.1", null);
        buffer.record("abc999", "GET", "/api/v1/play/1", 500, 3000L, 8L, "2.2.2.2", "db timeout");

        assertEquals(2, buffer.search("abc", null, null, null, null, 50).size());
        assertEquals(1, buffer.search("ABC1", null, null, null, null, 50).size());
        assertEquals(1, buffer.search(null, 7L, null, null, null, 50).size());
        assertEquals(1, buffer.search(null, null, "search", null, null, 50).size());
        assertEquals(1, buffer.search(null, null, null, 500, null, 50).size());
        assertEquals(1, buffer.search(null, null, null, null, 1000L, 50).size());

        List<OpsTraceBuffer.TraceRecord> limited = buffer.search(null, null, null, null, null, 1);
        assertEquals(1, limited.size());
        assertEquals("abc999", limited.get(0).getTraceId(), "最新记录排在最前");
    }

    @Test
    @DisplayName("容量固定：写满后覆盖最旧，不无限增长")
    void ringBufferOverwritesOldest() {
        for (int i = 1; i <= 105; i++) {
            buffer.record("trace-" + i, "GET", "/api/v1/songs", 200, 10L, null, null, null);
        }

        assertEquals(100, buffer.stats().get("capacity"));
        assertEquals(100, buffer.stats().get("filled"));
        assertEquals(105L, buffer.stats().get("recorded"));

        List<OpsTraceBuffer.TraceRecord> result = buffer.search(null, null, null, null, null, 500);
        assertEquals(100, result.size());
        assertEquals("trace-105", result.get(0).getTraceId());
        assertEquals("trace-6", result.get(99).getTraceId());
    }

    @Test
    @DisplayName("clear 清空缓冲并重置计数")
    void clearResets() {
        buffer.record("trace-1", "GET", "/api/v1/songs", 200, 10L, null, null, null);
        buffer.markDropped();

        buffer.clear();

        assertEquals(0, buffer.stats().get("filled"));
        assertEquals(0L, buffer.stats().get("recorded"));
        assertEquals(0L, buffer.stats().get("dropped"));
        assertTrue(buffer.search(null, null, null, null, null, 50).isEmpty());
    }

    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
