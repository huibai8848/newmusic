package com.example.newmusic.module.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐模块配置（{@code newmusic.recommend.*}）。
 *
 * <p>默认值直接对齐 PRD 17 的 BR 编号，YAML 里只写需要覆盖的项即可。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.recommend")
public class RecommendProperties {

    /** BR-17-001：每日推荐固定 30 首 */
    private int dailySize = 30;
    /** BR-17-025：猜你喜欢每次 20 首 */
    private int guessSize = 20;
    /** 猜你喜欢一次生成的候选池，翻页时从这里切片 */
    private int guessCacheSize = 100;
    /** BR-17-031：相似歌曲 20 首 */
    private int similarSize = 20;
    /** 新人推荐条数 */
    private int newcomerSize = 20;
    /** BR-17-009：48 小时 */
    private int dailyCacheHours = 48;
    /** BR-17-023：30 分钟 */
    private int guessCacheMinutes = 30;
    private int newcomerCacheMinutes = 60;
    private int frequentCacheMinutes = 60;
    /** BR-17-036：相似数据保留 7 天 */
    private int similarCacheDays = 7;
    /** BR-17-007 */
    private int maxArtistPerList = 2;
    /** BR-17-027 */
    private int maxAlbumPerList = 1;
    /** BR-17-022 */
    private int dislikedSongLimit = 200;
    private int dislikedArtistLimit = 50;
    /** BR-17-050：10% 探索位 */
    private double explorationRatio = 0.10;
    /** BR-17-048：冷启动混入 50% 热门 */
    private double coldStartRatio = 0.50;
    /** BR-17-028：行为数据不足 5 条降级 */
    private int coldStartBehaviorThreshold = 5;
    /** 最近播放参与画像的条数 */
    private int recentPlayLimit = 500;
    /** BR-17-004：已听过的歌曲占比上限 30% */
    private double listenedRatioLimit = 0.30;
    /** BR-17-026：猜你喜欢已听占比上限 20% */
    private double guessListenedRatioLimit = 0.20;
    /** 有效播放口径 */
    private int validPlaySeconds = 30;
    private double validPlayRatio = 0.6;

    /** 相似歌曲离线预计算（每日 03:30） */
    private String similarPrecomputeCron = "0 30 3 * * ?";
    /** 每日推荐预热（00:10） */
    private String dailyPrewarmCron = "0 10 0 * * ?";
    /** 预热用户上限；生产环境由 XXL-Job 分片替代 */
    private int prewarmUserLimit = 1000;
    /** 本地关闭定时任务用（例如只想跑接口验证时） */
    private boolean jobsEnabled = true;
}
