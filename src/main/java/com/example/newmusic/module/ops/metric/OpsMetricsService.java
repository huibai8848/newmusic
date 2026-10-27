package com.example.newmusic.module.ops.metric;

import com.alibaba.druid.pool.DruidDataSource;
import com.example.newmusic.infra.redis.RedisGuard;
import com.example.newmusic.module.ops.breaker.CircuitBreakerService;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.service.DegradeService;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 应用指标（FR-28-007 / AC-28-007-1~10）。
 *
 * <p>本机没有 Prometheus 服务端，也没有 {@code micrometer-registry-prometheus} 依赖
 * （模块 04 已确认依赖白名单里没有它）。所以这里做两件事：
 * <ol>
 *   <li>进程内自己维护 RED 指标（Rate / Errors / Duration）与 JVM/DB/WS/依赖指标；</li>
 *   <li>以标准 Prometheus 文本格式输出，接上 Prometheus 后直接把
 *       {@code /api/v1/internal/ops/metrics} 配成 scrape target 即可，无需改业务代码。</li>
 * </ol>
 *
 * <p>指标名与 PRD 对齐（{@code ml_} 前缀 + {@code jvm_} / {@code process_} 标准名）。
 * 所有聚合结构都做了容量上限：路由维度最多 400 个 key，超出归入 {@code other}，
 * 避免攻击者用随机 URL 把指标表撑爆。
 */
@Slf4j
@Service
public class OpsMetricsService {

    private static final int WINDOW_SECONDS = 300;
    private static final int MAX_ROUTES = 400;
    private static final double[] LATENCY_BOUNDS_MS = {
            5, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000
    };

    private final DataSource dataSource;
    private final RedisGuard redisGuard;
    private final WsSessionRegistry wsSessionRegistry;
    private final CircuitBreakerService circuitBreakerService;
    private final DegradeService degradeService;
    private final ChaosState chaosState;
    private final ApplicationContext applicationContext;

    private final SecondWindow requestsWindow = new SecondWindow(WINDOW_SECONDS);
    private final SecondWindow errorsWindow = new SecondWindow(WINDOW_SECONDS);
    private final SecondWindow rateLimitedWindow = new SecondWindow(WINDOW_SECONDS);
    private final SecondWindow slowSqlWindow = new SecondWindow(WINDOW_SECONDS);
    private final Histogram globalLatency = new Histogram();

    private final LongAdder requestsTotal = new LongAdder();
    private final LongAdder errors5xxTotal = new LongAdder();
    private final LongAdder rateLimitedTotal = new LongAdder();
    private final LongAdder degradeBlockedTotal = new LongAdder();
    private final LongAdder chaosInjectedTotal = new LongAdder();
    private final LongAdder slowSqlTotal = new LongAdder();
    private final LongAdder sqlTotal = new LongAdder();
    private final LongAdder[] statusClasses = new LongAdder[6];
    private final Map<String, LongAdder> methodCounters = new ConcurrentHashMap<>();

    private final Map<String, RouteStat> routes = new ConcurrentHashMap<>();
    private final long startedAt = System.currentTimeMillis();

    public OpsMetricsService(DataSource dataSource, RedisGuard redisGuard,
                             WsSessionRegistry wsSessionRegistry,
                             CircuitBreakerService circuitBreakerService,
                             DegradeService degradeService,
                             ChaosState chaosState,
                             ApplicationContext applicationContext) {
        this.dataSource = dataSource;
        this.redisGuard = redisGuard;
        this.wsSessionRegistry = wsSessionRegistry;
        this.circuitBreakerService = circuitBreakerService;
        this.degradeService = degradeService;
        this.chaosState = chaosState;
        this.applicationContext = applicationContext;
        for (int i = 0; i < statusClasses.length; i++) {
            statusClasses[i] = new LongAdder();
        }
    }

    // ------------------------------------------------------------------ 写入

    public void recordRequest(String method, String path, int status, long durationMs) {
        long now = System.currentTimeMillis();
        requestsTotal.increment();
        requestsWindow.add(now);
        globalLatency.record(durationMs);
        int statusClass = Math.max(0, Math.min(5, status / 100));
        statusClasses[statusClass].increment();
        String methodKey = method == null ? "UNKNOWN" : method.toUpperCase(Locale.ROOT);
        methodCounters.computeIfAbsent(methodKey, k -> new LongAdder()).increment();
        if (status >= 500) {
            errors5xxTotal.increment();
            errorsWindow.add(now);
        }
        String route = normalizeRoute(path);
        if (route == null) {
            return;
        }
        RouteStat stat = routes.get(route);
        if (stat == null && routes.size() >= MAX_ROUTES) {
            route = "other";
            stat = routes.get(route);
        }
        if (stat == null) {
            stat = routes.computeIfAbsent(route, k -> new RouteStat());
        }
        stat.requests.increment();
        stat.latency.record(durationMs);
        if (status >= 500) {
            stat.errors.increment();
        }
        stat.lastStatus = status;
    }

    public void recordRateLimited() {
        rateLimitedTotal.increment();
        rateLimitedWindow.add(System.currentTimeMillis());
    }

    public void recordDegradeBlocked() {
        degradeBlockedTotal.increment();
    }

    public void recordChaosInjected() {
        chaosInjectedTotal.increment();
    }

    public void recordSql(long durationMs) {
        sqlTotal.increment();
        if (durationMs >= 1000L) {
            slowSqlTotal.increment();
            slowSqlWindow.add(System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ 读

    public long requestsTotal() {
        return requestsTotal.sum();
    }

    public long errors5xxTotal() {
        return errors5xxTotal.sum();
    }

    public long rateLimitedTotal() {
        return rateLimitedTotal.sum();
    }

    public long slowSqlTotal() {
        return slowSqlTotal.sum();
    }

    public long requestsInWindow(int seconds) {
        return requestsWindow.sum(seconds);
    }

    public long errorsInWindow(int seconds) {
        return errorsWindow.sum(seconds);
    }

    public long rateLimitedInWindow(int seconds) {
        return rateLimitedWindow.sum(seconds);
    }

    public long slowSqlInWindow(int seconds) {
        return slowSqlWindow.sum(seconds);
    }

    /** 5xx 错误率（0~1）；窗口内样本少于 10 时返回 0，避免低流量误报。 */
    public double errorRate(int seconds) {
        long total = requestsWindow.sum(seconds);
        if (total < 10) {
            return 0d;
        }
        return (double) errorsWindow.sum(seconds) / total;
    }

    public double p95LatencyMs() {
        return globalLatency.percentile(0.95);
    }

    public double p99LatencyMs() {
        return globalLatency.percentile(0.99);
    }

    public boolean redisAvailable() {
        return redisGuard.isAvailable();
    }

    public double heapUsageRatio() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getMax() <= 0 ? 0d : (double) heap.getUsed() / heap.getMax();
    }

    /** 磁盘可用比例（0~1），对应 DiskSpaceLow 告警。 */
    public double diskAvailableRatio() {
        File root = new File(".");
        long total = root.getTotalSpace();
        return total <= 0 ? 1d : (double) root.getUsableSpace() / total;
    }

    public int currentDegradeLevel() {
        return degradeService.currentLevel();
    }

    public boolean chaosRunning() {
        return chaosState.snapshot() != null;
    }

    // ------------------------------------------------------------------ 快照

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", System.currentTimeMillis());
        map.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);
        map.put("requestsTotal", requestsTotal.sum());
        map.put("errors5xxTotal", errors5xxTotal.sum());
        map.put("rateLimitedTotal", rateLimitedTotal.sum());
        map.put("degradeBlockedTotal", degradeBlockedTotal.sum());
        map.put("chaosInjectedTotal", chaosInjectedTotal.sum());
        map.put("slowSqlTotal", slowSqlTotal.sum());
        map.put("qps", requestsInWindow(5) / 5.0);
        map.put("errorRate60s", errorRate(60));
        map.put("p95Ms", p95LatencyMs());
        map.put("p99Ms", p99LatencyMs());
        map.put("requests60s", requestsInWindow(60));
        map.put("errors60s", errorsInWindow(60));
        map.put("rateLimited60s", rateLimitedInWindow(60));
        map.put("slowSql60s", slowSqlInWindow(60));
        map.put("redisAvailable", redisAvailable());
        map.put("degradeLevel", degradeService.currentLevel());
        map.put("degradeLevelName", degradeService.currentLevelName());
        map.put("chaosRunning", chaosRunning());
        map.put("circuitOpen", circuitBreakerService.openCount());
        map.put("wsConnections", wsSessionRegistry.totalConnections());
        map.put("wsRooms", wsSessionRegistry.totalRooms());
        map.put("jvm", jvm());
        map.put("dbPool", dbPool());
        map.put("disk", disk());
        map.put("routes", routeStats(50));
        return map;
    }

    public Map<String, Object> jvm() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("heapUsed", heap.getUsed());
        map.put("heapMax", heap.getMax());
        map.put("heapCommitted", heap.getCommitted());
        map.put("heapUsageRatio", heapUsageRatio());
        map.put("nonHeapUsed", nonHeap.getUsed());
        map.put("threads", threads.getThreadCount());
        map.put("peakThreads", threads.getPeakThreadCount());
        map.put("daemonThreads", threads.getDaemonThreadCount());
        map.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);
        map.put("startedAt", ManagementFactory.getRuntimeMXBean().getStartTime());
        long gcCount = 0;
        long gcTime = 0;
        List<Map<String, Object>> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0, gc.getCollectionCount());
            long time = Math.max(0, gc.getCollectionTime());
            gcCount += count;
            gcTime += time;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", gc.getName());
            item.put("count", count);
            item.put("timeMs", time);
            collectors.add(item);
        }
        map.put("gcCount", gcCount);
        map.put("gcTimeMs", gcTime);
        map.put("gcCollectors", collectors);
        return map;
    }

    public Map<String, Object> dbPool() {
        Map<String, Object> map = new LinkedHashMap<>();
        DruidDataSource druid = asDruid(dataSource);
        if (druid == null) {
            map.put("type", dataSource == null ? "none" : dataSource.getClass().getSimpleName());
            map.put("active", -1);
            return map;
        }
        map.put("type", "Druid");
        map.put("active", druid.getActiveCount());
        map.put("idle", druid.getPoolingCount());
        map.put("max", druid.getMaxActive());
        map.put("waiting", druid.getWaitThreadCount());
        map.put("connectCount", druid.getConnectCount());
        map.put("closeCount", druid.getCloseCount());
        map.put("usageRatio", druid.getMaxActive() <= 0 ? 0d
                : (double) druid.getActiveCount() / druid.getMaxActive());
        return map;
    }

    public Map<String, Object> disk() {
        File root = new File(".");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", root.getAbsolutePath());
        map.put("totalBytes", root.getTotalSpace());
        map.put("usableBytes", root.getUsableSpace());
        map.put("availableRatio", diskAvailableRatio());
        return map;
    }

    public List<Map<String, Object>> routeStats(int limit) {
        List<Map.Entry<String, RouteStat>> entries = new ArrayList<>(routes.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().requests.sum(), a.getValue().requests.sum()));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, RouteStat> entry : entries) {
            if (list.size() >= limit) {
                break;
            }
            RouteStat stat = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("route", entry.getKey());
            item.put("requests", stat.requests.sum());
            item.put("errors", stat.errors.sum());
            item.put("errorRate", stat.requests.sum() == 0 ? 0d
                    : (double) stat.errors.sum() / stat.requests.sum());
            item.put("p95Ms", stat.latency.percentile(0.95));
            item.put("avgMs", stat.latency.average());
            item.put("lastStatus", stat.lastStatus);
            list.add(item);
        }
        return list;
    }

    /** 线程池视图（AC-28-007-1 的「容器/进程」层）。只报告项目自己定义的执行器。 */
    public List<Map<String, Object>> threadPools() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Executor> beans = applicationContext.getBeansOfType(Executor.class);
        for (Map.Entry<String, Executor> entry : beans.entrySet()) {
            ThreadPoolExecutor pool = unwrap(entry.getValue());
            if (pool == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", entry.getKey());
            map.put("poolSize", pool.getPoolSize());
            map.put("active", pool.getActiveCount());
            map.put("core", pool.getCorePoolSize());
            map.put("max", pool.getMaximumPoolSize());
            map.put("queueSize", pool.getQueue().size());
            map.put("queueRemaining", pool.getQueue().remainingCapacity());
            map.put("completed", pool.getCompletedTaskCount());
            map.put("largestPoolSize", pool.getLargestPoolSize());
            map.put("rejectedPolicy", pool.getRejectedExecutionHandler().getClass().getSimpleName());
            int max = pool.getMaximumPoolSize();
            map.put("usageRatio", max <= 0 ? 0d : (double) pool.getActiveCount() / max);
            list.add(map);
        }
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return list;
    }

    // ------------------------------------------------------------------ Prometheus

    public String prometheus() {
        StringBuilder sb = new StringBuilder(8192);
        appendType(sb, "ml_http_requests_total", "counter", "Total HTTP requests");
        for (int i = 1; i < statusClasses.length; i++) {
            sb.append("ml_http_requests_total{status=\"").append(i).append("xx\"} ")
                    .append(statusClasses[i].sum()).append('\n');
        }
        appendType(sb, "ml_http_requests_by_method_total", "counter", "HTTP requests by method");
        for (Map.Entry<String, LongAdder> entry : methodCounters.entrySet()) {
            sb.append("ml_http_requests_by_method_total{method=\"").append(escape(entry.getKey()))
                    .append("\"} ").append(entry.getValue().sum()).append('\n');
        }

        appendType(sb, "ml_http_request_duration_seconds", "histogram", "HTTP request latency");
        globalLatency.appendPrometheus(sb, "ml_http_request_duration_seconds");

        appendType(sb, "ml_http_errors_5xx_total", "counter", "HTTP 5xx responses");
        sb.append("ml_http_errors_5xx_total ").append(errors5xxTotal.sum()).append('\n');
        appendType(sb, "ml_http_error_rate_60s", "gauge", "5xx error rate over 60s");
        sb.append("ml_http_error_rate_60s ").append(errorRate(60)).append('\n');

        appendType(sb, "ml_rate_limited_total", "counter", "Requests rejected by rate limiter");
        sb.append("ml_rate_limited_total ").append(rateLimitedTotal.sum()).append('\n');
        appendType(sb, "ml_degrade_blocked_total", "counter", "Requests blocked by degrade switches");
        sb.append("ml_degrade_blocked_total ").append(degradeBlockedTotal.sum()).append('\n');
        appendType(sb, "ml_chaos_injected_total", "counter", "Chaos fault injections");
        sb.append("ml_chaos_injected_total ").append(chaosInjectedTotal.sum()).append('\n');
        appendType(sb, "ml_slow_sql_total", "counter", "SQL statements slower than 1s");
        sb.append("ml_slow_sql_total ").append(slowSqlTotal.sum()).append('\n');
        appendType(sb, "ml_sql_total", "counter", "SQL statements executed");
        sb.append("ml_sql_total ").append(sqlTotal.sum()).append('\n');

        appendType(sb, "ml_degrade_level", "gauge", "Current degrade level (0-5)");
        sb.append("ml_degrade_level ").append(degradeService.currentLevel()).append('\n');
        appendType(sb, "ml_chaos_running", "gauge", "Running chaos experiments");
        sb.append("ml_chaos_running ").append(chaosRunning() ? 1 : 0).append('\n');
        appendType(sb, "ml_redis_available", "gauge", "Redis guard state (1=available)");
        sb.append("ml_redis_available ").append(redisAvailable() ? 1 : 0).append('\n');
        appendType(sb, "ml_ws_connections", "gauge", "Current WebSocket connections");
        sb.append("ml_ws_connections ").append(wsSessionRegistry.totalConnections()).append('\n');
        appendType(sb, "ml_ws_rooms", "gauge", "Rooms with local WS subscribers");
        sb.append("ml_ws_rooms ").append(wsSessionRegistry.totalRooms()).append('\n');

        appendType(sb, "ml_circuit_open", "gauge", "Circuit breaker state (1=open/half-open)");
        for (Map<String, Object> item : circuitBreakerService.snapshot()) {
            int open = "CLOSED".equals(item.get("state")) ? 0 : 1;
            sb.append("ml_circuit_open{name=\"").append(escape(String.valueOf(item.get("name"))))
                    .append("\"} ").append(open).append('\n');
        }

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        appendType(sb, "jvm_memory_used_bytes", "gauge", "JVM memory used");
        sb.append("jvm_memory_used_bytes{area=\"heap\"} ").append(heap.getUsed()).append('\n');
        appendType(sb, "jvm_memory_max_bytes", "gauge", "JVM memory max");
        sb.append("jvm_memory_max_bytes{area=\"heap\"} ").append(heap.getMax()).append('\n');
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        appendType(sb, "jvm_threads_live", "gauge", "Live threads");
        sb.append("jvm_threads_live ").append(threads.getThreadCount()).append('\n');
        appendType(sb, "jvm_threads_peak", "gauge", "Peak threads");
        sb.append("jvm_threads_peak ").append(threads.getPeakThreadCount()).append('\n');
        appendType(sb, "jvm_gc_collection_seconds_sum", "counter", "GC collection time");
        appendType(sb, "jvm_gc_collection_count_total", "counter", "GC collection count");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = escape(gc.getName());
            sb.append("jvm_gc_collection_seconds_sum{name=\"").append(name).append("\"} ")
                    .append(Math.max(0, gc.getCollectionTime()) / 1000.0).append('\n');
            sb.append("jvm_gc_collection_count_total{name=\"").append(name).append("\"} ")
                    .append(Math.max(0, gc.getCollectionCount())).append('\n');
        }
        appendType(sb, "process_uptime_seconds", "gauge", "Process uptime");
        sb.append("process_uptime_seconds ")
                .append((System.currentTimeMillis() - startedAt) / 1000L).append('\n');
        appendType(sb, "process_start_time_seconds", "gauge", "Process start time");
        sb.append("process_start_time_seconds ")
                .append(ManagementFactory.getRuntimeMXBean().getStartTime() / 1000L).append('\n');

        DruidDataSource druid = asDruid(dataSource);
        if (druid != null) {
            appendType(sb, "ml_db_pool_active", "gauge", "Druid active connections");
            sb.append("ml_db_pool_active ").append(druid.getActiveCount()).append('\n');
            appendType(sb, "ml_db_pool_idle", "gauge", "Druid idle connections");
            sb.append("ml_db_pool_idle ").append(druid.getPoolingCount()).append('\n');
            appendType(sb, "ml_db_pool_max", "gauge", "Druid max connections");
            sb.append("ml_db_pool_max ").append(druid.getMaxActive()).append('\n');
            appendType(sb, "ml_db_pool_waiting", "gauge", "Threads waiting for a connection");
            sb.append("ml_db_pool_waiting ").append(druid.getWaitThreadCount()).append('\n');
        }
        appendType(sb, "ml_disk_avail_bytes", "gauge", "Usable disk bytes");
        sb.append("ml_disk_avail_bytes ").append(new File(".").getUsableSpace()).append('\n');
        appendType(sb, "ml_disk_total_bytes", "gauge", "Total disk bytes");
        sb.append("ml_disk_total_bytes ").append(new File(".").getTotalSpace()).append('\n');

        appendType(sb, "ml_http_route_requests_total", "counter", "Requests by normalized route");
        appendType(sb, "ml_http_route_p95_ms", "gauge", "P95 latency by normalized route");
        for (Map<String, Object> item : routeStats(30)) {
            String route = escape(String.valueOf(item.get("route")));
            sb.append("ml_http_route_requests_total{route=\"").append(route).append("\"} ")
                    .append(item.get("requests")).append('\n');
            sb.append("ml_http_route_p95_ms{route=\"").append(route).append("\"} ")
                    .append(item.get("p95Ms")).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    private static void appendType(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeRoute(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        if (path.startsWith("/actuator") || path.startsWith("/media/")
                || path.startsWith("/api/v1/internal/ops")) {
            return null;
        }
        String clean = path;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        String[] segments = clean.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/');
            if (segment.matches("\\d+") || segment.matches("[0-9a-fA-F]{16,}")) {
                sb.append("{id}");
            } else if (segment.length() > 40) {
                sb.append("{var}");
            } else {
                sb.append(segment);
            }
        }
        String route = sb.length() == 0 ? "/" : sb.toString();
        return route.length() > 120 ? route.substring(0, 120) : route;
    }

    private static DruidDataSource asDruid(DataSource source) {
        if (source == null) {
            return null;
        }
        if (source instanceof DruidDataSource) {
            return (DruidDataSource) source;
        }
        try {
            return source.unwrap(DruidDataSource.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ThreadPoolExecutor unwrap(Executor executor) {
        if (executor instanceof ThreadPoolExecutor) {
            return (ThreadPoolExecutor) executor;
        }
        if (executor instanceof ThreadPoolTaskExecutor) {
            try {
                return ((ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 按秒分桶的滑动计数，容量固定，读写都在同一把锁内。 */
    static final class SecondWindow {
        private final long[] seconds;
        private final long[] counts;

        SecondWindow(int size) {
            this.seconds = new long[size];
            this.counts = new long[size];
            Arrays.fill(seconds, Long.MIN_VALUE);
        }

        synchronized void add(long nowMillis) {
            long nowSecond = nowMillis / 1000L;
            int index = (int) Math.floorMod(nowSecond, seconds.length);
            if (seconds[index] != nowSecond) {
                seconds[index] = nowSecond;
                counts[index] = 0;
            }
            counts[index]++;
        }

        synchronized long sum(int windowSeconds) {
            long nowSecond = System.currentTimeMillis() / 1000L;
            long from = nowSecond - Math.max(1, windowSeconds) + 1;
            long total = 0;
            for (int i = 0; i < seconds.length; i++) {
                if (seconds[i] >= from && seconds[i] <= nowSecond) {
                    total += counts[i];
                }
            }
            return total;
        }
    }

    /** 固定桶延迟直方图（Prometheus histogram + 分位数估算）。 */
    static final class Histogram {
        private final long[] bucketCounts = new long[LATENCY_BOUNDS_MS.length + 1];
        private long count;
        private long sumNanos;
        private long maxNanos;

        synchronized void record(long durationMs) {
            long nanos = Math.max(0, durationMs) * 1_000_000L;
            count++;
            sumNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
            int bucket = LATENCY_BOUNDS_MS.length;
            for (int i = 0; i < LATENCY_BOUNDS_MS.length; i++) {
                if (durationMs <= LATENCY_BOUNDS_MS[i]) {
                    bucket = i;
                    break;
                }
            }
            bucketCounts[bucket]++;
        }

        synchronized double percentile(double p) {
            if (count == 0) {
                return 0d;
            }
            double target = count * p;
            long cumulative = 0;
            for (int i = 0; i < bucketCounts.length; i++) {
                cumulative += bucketCounts[i];
                if (cumulative >= target) {
                    return i < LATENCY_BOUNDS_MS.length
                            ? LATENCY_BOUNDS_MS[i]
                            : Math.max(LATENCY_BOUNDS_MS[LATENCY_BOUNDS_MS.length - 1], maxNanos / 1_000_000d);
                }
            }
            return maxNanos / 1_000_000d;
        }

        synchronized double average() {
            return count == 0 ? 0d : sumNanos / 1_000_000d / count;
        }

        synchronized void appendPrometheus(StringBuilder sb, String name) {
            long cumulative = 0;
            for (int i = 0; i < LATENCY_BOUNDS_MS.length; i++) {
                cumulative += bucketCounts[i];
                sb.append(name).append("_bucket{le=\"")
                        .append(formatBound(LATENCY_BOUNDS_MS[i] / 1000d))
                        .append("\"} ").append(cumulative).append('\n');
            }
            cumulative += bucketCounts[bucketCounts.length - 1];
            sb.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
            sb.append(name).append("_sum ").append(sumNanos / 1_000_000_000d).append('\n');
            sb.append(name).append("_count ").append(count).append('\n');
        }

        private static String formatBound(double seconds) {
            if (seconds == Math.floor(seconds)) {
                return String.valueOf((long) seconds);
            }
            return String.valueOf(seconds);
        }
    }

    static final class RouteStat {
        private final LongAdder requests = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final Histogram latency = new Histogram();
        private volatile int lastStatus;
    }
}
