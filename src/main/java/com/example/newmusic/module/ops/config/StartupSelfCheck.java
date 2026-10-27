package com.example.newmusic.module.ops.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 启动自检（FR-28-011 / AC-28-011-5）。
 *
 * <p>模块 19 的所有能力都依赖 {@code sql/19-ops-seed.sql}：缺表时服务虽然能启动，但降级、
 * 限流、告警都会静默失效——这比启动失败更危险。所以这里在启动时校验表与关键配置，
 * 缺失就直接失败并打印修复命令。
 *
 * <p>表名全部来自常量白名单，不拼接任何外部输入。可用
 * {@code newmusic.ops.startup-check-enabled=false} 关闭（例如只跑单元测试时）。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class StartupSelfCheck implements ApplicationRunner {

    private static final List<String> REQUIRED_TABLES = Arrays.asList(
            "t_ops_rate_limit_rule",
            "t_degrade_switch",
            "t_degrade_event",
            "t_ops_alert_rule",
            "t_ops_alert_record",
            "t_ops_alert_silence",
            "t_publish_record",
            "t_backup_record",
            "t_chaos_experiment",
            "t_cost_record",
            "t_ops_runbook",
            "t_ops_metric_snapshot"
    );

    private static final List<String> REQUIRED_CONFIG_KEYS = Arrays.asList(
            "ops.rate.limit.enabled",
            "ops.degrade.level",
            "ops.trace.sample-rate",
            "ops.alert.eval.enabled"
    );

    private final DataSource dataSource;
    private final OpsProperties properties;

    public StartupSelfCheck(DataSource dataSource, OpsProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || !properties.isStartupCheckEnabled()) {
            log.info("模块19启动自检已关闭");
            return;
        }
        List<String> problems = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : REQUIRED_TABLES) {
                if (!tableExists(connection, table)) {
                    problems.add("缺表 newmusic." + table);
                }
            }
            if (problems.isEmpty()) {
                for (String key : REQUIRED_CONFIG_KEYS) {
                    if (!configExists(connection, key)) {
                        problems.add("缺配置 t_sys_config: " + key);
                    }
                }
            }
        } catch (Exception e) {
            log.error("模块19启动自检无法连接数据库 cause={}", e.toString());
            throw new IllegalStateException("模块19启动自检失败：数据库不可用", e);
        }
        if (!problems.isEmpty()) {
            log.error("模块19启动自检失败，请先执行 sql/19-ops-seed.sql：{}", problems);
            throw new IllegalStateException("模块19启动自检失败：" + String.join("; ", problems));
        }
        log.info("模块19启动自检通过 tables={} configs={}", REQUIRED_TABLES.size(), REQUIRED_CONFIG_KEYS.size());
    }

    private boolean tableExists(Connection connection, String table) {
        String sql = "SELECT 1 FROM newmusic." + table + " LIMIT 1";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (ResultSet rs = statement.executeQuery(sql)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("启动自检表不可用 table={} cause={}", table, e.toString());
            return false;
        }
    }

    private boolean configExists(Connection connection, String key) {
        String sql = "SELECT 1 FROM newmusic.t_sys_config WHERE config_key = '" + key
                + "' AND status = 1 LIMIT 1";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (ResultSet rs = statement.executeQuery(sql)) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("启动自检配置不可用 key={} cause={}", key, e.toString());
            return false;
        }
    }
}
