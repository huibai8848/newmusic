package com.example.newmusic.module.dataplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据平台配置（模块 17）。
 *
 * <p>默认值按 PRD 26 的规则取值；本机没有 Kafka/ClickHouse，采集走同步写 MySQL，
 * {@code sync-persist=false} 时切到有界线程池，行为与生产的异步消费一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.data-platform")
public class DataPlatformProperties {

    /** 定时任务总开关。默认关闭：验收入口全部支持手工触发，避免测试环境半夜跑批。 */
    private boolean jobsEnabled = false;

    /**
     * true = 采集请求内同步落库（本机默认，便于验收时立即查库）；
     * false = 提交到 dataPlatformExecutor 异步落库（生产语义，BR-26-006）。
     */
    private boolean syncPersist = true;

    /** event_id 幂等窗口（BR-26-004） */
    private int dedupSeconds = 300;

    /** 单设备每分钟上报上限（EX-26-001-4：100 事件/分钟） */
    private int rateLimitPerMinute = 100;

    /** 单次批量上限 */
    private int batchMax = 200;

    /** 业务属性 JSON 大小上限，超过进脏数据 */
    private int maxPropsBytes = 8192;

    /** 新用户窗口（BR-26-037，默认 7 天） */
    private int newUserDays = 7;

    /** 有效播放阈值：≥30 秒（BR-26-031） */
    private int validPlaySeconds = 30;

    /** 有效播放阈值：完成度 ≥ 60% */
    private double validPlayRatio = 0.6;

    /** DAU-Play：当日有效播放 ≥ 3 首（PRD 26 指标卡） */
    private int dauPlayThreshold = 3;

    /** 实时指标键保留天数 */
    private int realtimeTtlDays = 7;

    /** 漏斗单次最多扫描的事件行数（保护内存） */
    private int funnelMaxRows = 200000;

    /** 留存回溯天数 */
    private int retentionDays = 60;

    /** 留存单次最多扫描行数 */
    private int retentionMaxRows = 300000;

    /** 人群圈选精度保护阈值（BR-26-044） */
    private int audienceMinPrecision = 100;

    /** 人群圈选最大扫描行数 */
    private int audienceMaxRows = 500000;

    /** 画像任务单次处理用户数 */
    private int profileBatchUsers = 5000;

    /** 画像标签版本前缀（实际版本为 v + yyyyMMdd） */
    private String profileTagVersion = "v";

    private String etlCron = "0 20 0 * * ?";
    private String profileCron = "0 40 0 * * ?";
    private String qualityCron = "0 10 1 * * ?";
    private String reconciliationCron = "0 40 1 * * ?";
    private String reportCron = "0 0 7 * * ?";
}
