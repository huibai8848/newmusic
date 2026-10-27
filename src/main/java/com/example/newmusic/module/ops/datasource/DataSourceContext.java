package com.example.newmusic.module.ops.datasource;

/**
 * 数据源路由上下文（FR-28-006）。
 *
 * <p>两条规则：
 * <ol>
 *   <li>标注 {@link Slave} 的方法意图走从库；未标注的默认主库；</li>
 *   <li><b>写后读主</b>：同一请求线程只要执行过一次写 SQL，后续读一律主库（BR-28-060）。
 *       标记在请求结束时由 {@code OpsRequestInterceptor} 清理，避免线程复用污染下一个请求。</li>
 * </ol>
 */
public final class DataSourceContext {

    private static final ThreadLocal<DataSourceType> TYPE = new ThreadLocal<>();

    private DataSourceContext() {
    }

    public static void set(DataSourceType type) {
        TYPE.set(type);
    }

    public static DataSourceType get() {
        return TYPE.get();
    }

    /** 写操作后调用：本请求后续读全部走主库。 */
    public static void markWrite() {
        TYPE.set(DataSourceType.MASTER);
    }

    /** 当前线程是否标记过写操作（写后读主是否生效）。 */
    public static boolean isWriteAfterRead() {
        return TYPE.get() == DataSourceType.MASTER;
    }

    /** 实际路由目标：显式标记优先，否则主库。 */
    public static DataSourceType route() {
        return TYPE.get() == null ? DataSourceType.MASTER : TYPE.get();
    }

    public static void clear() {
        TYPE.remove();
    }
}
