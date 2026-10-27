package com.example.newmusic.module.ops.datasource;

/**
 * 数据源路由目标（FR-28-006 / BR-28-060）。
 *
 * <p>本机只有一个 MySQL 实例，没有从库。这里先把「路由决策」做成独立、可测试的一层：
 * 接入从库时，只需让真正的 RoutingDataSource 读取 {@link DataSourceContext#current()}；
 * 在此之前，决策结果通过运维接口暴露，用于验收「写后读主」标记是否生效。
 */
public enum DataSourceType {
    MASTER,
    SLAVE
}
