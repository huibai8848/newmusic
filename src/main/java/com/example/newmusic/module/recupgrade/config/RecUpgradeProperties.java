package com.example.newmusic.module.recupgrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐升级模块配置（{@code newmusic.rec-upgrade.*}）。
 *
 * <p>所有阈值都有默认值，YAML 里只写需要覆盖的项。业务可调的权重、算子链、
 * 实验显著性阈值不放在这里，它们在 {@code t_rec_config} 里，支持热更新。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.rec-upgrade")
public class RecUpgradeProperties {

    /** BR-24-003：召回阶段全局 deadline */
    private int recallDeadlineMs = 150;
    /** BR-24-001：单源默认超时 */
    private int sourceDefaultTimeoutMs = 100;
    /** 每个召回源默认召回量 */
    private int recallSourceLimit = 300;
    /** BR-24-023：融合后候选上限 */
    private int fusionTopN = 500;
    /** BR-24-026：粗排目标数量 */
    private int coarseTarget = 150;
    /** 精排阶段超时（超时用已完成结果） */
    private int fineRankTimeoutMs = 80;
    /** 重排阶段超时（仅用于埋点告警，不强行截断） */
    private int rerankTimeoutMs = 30;
    /** 最终返回条数 */
    private int finalSize = 30;
    /** C 端一次请求允许的最大条数 */
    private int maxRequestSize = 100;
    /** BR-24-047：特征获取整体超时 */
    private int featureDeadlineMs = 20;
    /** BR-24-050：用户画像 TTL（小时） */
    private int featureUserTtlHours = 25;
    /** BR-24-050：歌曲统计 TTL（分钟） */
    private int featureSongTtlMinutes = 10;
    /** BR-24-031：模型版本检查间隔（秒） */
    private int modelReloadSeconds = 30;

    /** BR-24-083 的默认值；实际以 t_rec_config GLOBAL/EXPERIMENT 为准 */
    private int experimentMinSample = 10000;
    /** 实验/指标数据缓存时间（秒） */
    private int experimentCacheSeconds = 30;
    /** t_rec_log / t_rec_event 保留天数 */
    private int logRetentionDays = 7;

    /** 新歌扶持：发布 N 天内 */
    private int newSongDays = 7;
    /** BR-24-063：每日扶持新歌上限 */
    private int newSongDailyCap = 50;
    /** 新歌评估曝光目标 */
    private int newSongExposureTarget = 1000;
    /** BR-24-061：完成率低于该值停止扶持 */
    private double newSongCompletionThreshold = 0.30;
    /** 新歌单用户 24h 曝光上限（BR-24-060） */
    private int newSongUserExposureLimit = 2;

    /** 探索占比默认值（BR-24-065，可在配置里覆盖） */
    private double exploreRatio = 0.10;
    /** 探索位从第几个位置开始（1 基；BR-24-066） */
    private int exploreStartPosition = 8;

    /** 离线评估最多扫描的用户数 */
    private int evaluateMaxUsers = 500;
    /** 离线评估：训练天数 */
    private int evaluateTrainDays = 6;

    /** 本地关闭定时任务用（与模块 08 的开关同名习惯） */
    private boolean jobsEnabled = true;
    private String dailyAggregateCron = "0 20 0 * * ?";
    private String logCleanupCron = "0 40 4 * * ?";
    private String newSongEvaluateCron = "0 0/10 * * * ?";
    private String featureCheckCron = "0 10 5 * * ?";
}
