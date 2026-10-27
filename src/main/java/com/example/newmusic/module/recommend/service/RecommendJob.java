package com.example.newmusic.module.recommend.service;

import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 推荐离线任务。
 *
 * <p>PRD 的目标调度平台是 XXL-Job；这里用 {@code @Scheduled} 提供同样的两个任务：
 * <ul>
 *   <li>00:10 为最近 7 天有行为的用户预生成每日推荐；</li>
 *   <li>03:30 重算全量相似歌曲矩阵。</li>
 * </ul>
 * 接入调度平台后把本类的方法迁走即可，业务逻辑都在 Service 里。
 */
@Slf4j
@Component
public class RecommendJob {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DailyRecommendService dailyRecommendService;
    private final SimilarRecommendService similarRecommendService;
    private final PlayHistoryMapper playHistoryMapper;
    private final RecommendProperties properties;

    public RecommendJob(DailyRecommendService dailyRecommendService,
                        SimilarRecommendService similarRecommendService,
                        PlayHistoryMapper playHistoryMapper,
                        RecommendProperties properties) {
        this.dailyRecommendService = dailyRecommendService;
        this.similarRecommendService = similarRecommendService;
        this.playHistoryMapper = playHistoryMapper;
        this.properties = properties;
    }

    @Scheduled(cron = "${newmusic.recommend.daily-prewarm-cron:0 10 0 * * ?}")
    public void prewarmDaily() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        String date = LocalDate.now().format(DATE);
        List<Long> userIds = playHistoryMapper.selectActiveUserIds(
                LocalDateTime.now().minusDays(7), properties.getPrewarmUserLimit());
        int ok = 0;
        int failed = 0;
        for (Long userId : userIds) {
            try {
                dailyRecommendService.generateAndStore(userId, date);
                ok++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("每日推荐预热失败 userId={} cause={}", userId, e.toString());
            }
        }
        log.info("每日推荐预热完成 date={} users={} ok={} failed={}", date, userIds.size(), ok, failed);
    }

    @Scheduled(cron = "${newmusic.recommend.similar-precompute-cron:0 30 3 * * ?}")
    public void precomputeSimilar() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            int count = similarRecommendService.precomputeAll();
            log.info("相似歌曲矩阵重算完成 songs={}", count);
        } catch (RuntimeException e) {
            // 预计算失败保留旧数据（EX-17-004-3），不影响在线查询
            log.error("相似歌曲矩阵重算失败，继续使用旧数据 cause={}", e.toString());
        }
    }
}
