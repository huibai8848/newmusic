package com.example.newmusic.module.ops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模块 19 运维配置（{@code newmusic.ops.*}）。
 *
 * <p>这些是**进程级**旋钮：真正的运行期规则（限流阈值、降级开关、告警阈值）
 * 在数据库里，改完 2~5 秒生效。这里只放「本实例自己的行为」——trace 缓冲大小、
 * 慢 SQL 阈值、混沌实验上限等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.ops")
public class OpsProperties {

    /** 总开关：关闭后 ops 拦截器只做指标采集，不做限流/降级拦截。 */
    private boolean enabled = true;

    /** 启动自检。失败即拒绝启动（BR-28-171）。 */
    private boolean startupCheckEnabled = true;

    /** Prometheus 文本端点的来源白名单（与模块 11 的内部词典接口同一套做法）。 */
    private String internalIps = "127.0.0.1,0:0:0:0:0:0:0:1,::1";

    private int traceBufferSize = 2000;
    private double traceSampleRate = 0.1;

    private int rateRuleRefreshSeconds = 3;
    private int degradeRefreshSeconds = 2;
    private int configRefreshSeconds = 5;

    /** Redis 故障时的单机滑动窗口兜底（AC-28-106）。 */
    private boolean localFallbackEnabled = true;

    /** 慢 SQL 只告警；超过 block 阈值时计数并写入错慢日志，不阻断已执行的查询。 */
    private long slowSqlWarnMs = 1000;
    private long slowSqlBlockMs = 5000;

    private long alertEvalIntervalMs = 5000;
    private long metricsSnapshotIntervalMs = 60000;

    /** 混沌实验默认开启仅限本机演示；生产必须用环境变量关闭。 */
    private boolean chaosEnabled = true;
    private int chaosMaxDurationSeconds = 120;
    private int chaosMaxBlastRadius = 100;

    /** 优雅停机：先给 WS 客户端发一帧再关闭，留一点时间让 TCP 缓冲刷出去。 */
    private long wsShutdownPauseMs = 300;

    /** 备份清单目录（相对仓库根；只写清单，不跑 mysqldump）。 */
    private String backupDir = ".tmp/backups";
    private int backupRetentionDays = 7;

    private Circuit circuit = new Circuit();

    @Data
    public static class Circuit {
        private double failureRateThreshold = 0.5;
        private int minimumCalls = 10;
        private int openSeconds = 10;
        private int halfOpenMaxCalls = 3;
    }
}
