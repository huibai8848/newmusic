package com.example.newmusic.module.ops.metric;

import com.example.newmusic.module.ops.chaos.ChaosState;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.datasource.DataSourceContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SQL 观测 + 慢 SQL 日志 + 主从路由标记 + SlowDB 混沌注入。
 *
 * <p>只做横切，不改变 SQL 与结果：
 * <ul>
 *   <li>写语句给 {@link DataSourceContext} 打 write-after-read 标记，避免同一请求内
 *       先读从库再写主库的脏读；</li>
 *   <li>耗时 ≥ {@code slow-sql-warn-ms} 写 WARN（只记 statement id，不记参数，
 *       防止把手机号/口令打进日志）；≥ {@code slow-sql-block-ms} 升级为 ERROR；</li>
 *   <li>SlowDB 混沌按爆炸半径注入固定延迟，上限 1 秒，避免把线程池全占死。</li>
 * </ul>
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class})
})
public class OpsDbInterceptor implements Interceptor {

    private static final long MAX_CHAOS_DELAY_MS = 1000L;

    /**
     * 这里必须用 {@link ObjectProvider} 延迟取指标服务：MyBatis 拦截器是
     * {@code SqlSessionFactory} 的构造参数，而指标服务间接依赖 Mapper（也就是
     * SqlSessionFactory 自己的代理）。直接注入会在启动时形成循环依赖。
     */
    private final ObjectProvider<OpsMetricsService> metricsProvider;
    private final ChaosState chaosState;
    private final OpsProperties properties;

    public OpsDbInterceptor(ObjectProvider<OpsMetricsService> metricsProvider, ChaosState chaosState,
                            OpsProperties properties) {
        this.metricsProvider = metricsProvider;
        this.chaosState = chaosState;
        this.properties = properties;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        if (statement != null && statement.getSqlCommandType() != SqlCommandType.SELECT) {
            DataSourceContext.markWrite();
        }
        OpsMetricsService metrics = metricsProvider.getIfAvailable();
        if (metrics != null && chaosState.shouldInject(ChaosState.FAULT_SLOW_DB)) {
            metrics.recordChaosInjected();
            sleep(MAX_CHAOS_DELAY_MS);
        }
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            if (metrics != null) {
                metrics.recordSql(duration);
            }
            if (duration >= properties.getSlowSqlBlockMs()) {
                log.error("慢 SQL 阻塞级 duration={}ms statement={}", duration, statementId(statement));
            } else if (duration >= properties.getSlowSqlWarnMs()) {
                log.warn("慢 SQL duration={}ms statement={}", duration, statementId(statement));
            }
        }
    }

    private static String statementId(MappedStatement statement) {
        return statement == null ? "-" : statement.getId();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无外部配置
    }
}
