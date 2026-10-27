package com.example.newmusic.module.ai.job;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.mapper.SongEmotionMapper;
import com.example.newmusic.module.ai.service.LyricTranslationService;
import com.example.newmusic.module.ai.service.SongEmotionService;
import com.example.newmusic.module.ai.support.AiFailureQueue;
import com.example.newmusic.module.ai.support.AiJobProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线 AI 批处理（FR-23-008）。
 *
 * <p>两个任务：02:00 情绪标注、02:30 热门非中文歌曲翻译预生成。都刻意放在低峰时段
 * （BR-23-085），并且每首之间 sleep {@code batchIntervalMs}（BR-23-080），
 * 避免把上游 QPS 打满——批处理把接口打挂是这类功能最常见的事故。
 *
 * <p><b>同一任务不允许并发。</b>{@link AtomicBoolean} 而不是数据库锁：任务是单实例
 * 定时触发的，进程内互斥足够；后台手动触发与定时触发撞车时，后来者收到 60007，
 * 而不是两份任务同时烧钱。
 */
@Slf4j
@Component
public class AiAnnotationJob {

    private final AiProperties properties;
    private final SongEmotionService emotionService;
    private final LyricTranslationService translationService;
    private final SongEmotionMapper emotionMapper;
    private final AiJobProgress jobProgress;
    private final AiFailureQueue failureQueue;

    private final AtomicBoolean emotionRunning = new AtomicBoolean(false);
    private final AtomicBoolean translationRunning = new AtomicBoolean(false);

    public AiAnnotationJob(AiProperties properties, SongEmotionService emotionService,
                           LyricTranslationService translationService,
                           SongEmotionMapper emotionMapper, AiJobProgress jobProgress,
                           AiFailureQueue failureQueue) {
        this.properties = properties;
        this.emotionService = emotionService;
        this.translationService = translationService;
        this.emotionMapper = emotionMapper;
        this.jobProgress = jobProgress;
        this.failureQueue = failureQueue;
    }

    @Scheduled(cron = "${newmusic.ai.annotation-cron:0 0 2 * * ?}")
    public void scheduledEmotion() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            runEmotion();
        } catch (BizException e) {
            // 正在执行：正常情况，不刷 ERROR
            log.info("情绪标注任务跳过：{}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("情绪标注任务失败", e);
        }
    }

    @Scheduled(cron = "${newmusic.ai.translation-prewarm-cron:0 30 2 * * ?}")
    public void scheduledTranslationPrewarm() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            runTranslationPrewarm();
        } catch (BizException e) {
            log.info("翻译预生成任务跳过：{}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("翻译预生成任务失败", e);
        }
    }

    /** 后台手动触发：情绪标注。{@code limit} 为空时用配置值。 */
    public Map<String, Object> runEmotion(Integer limit) {
        if (!emotionRunning.compareAndSet(false, true)) {
            throw new BizException(ResultCode.AI_JOB_RUNNING);
        }
        try {
            int max = limit == null ? properties.getAnnotationMaxPerRun() : Math.max(1, limit);
            Map<String, Object> summary = emotionService.annotateBatch(null, max);
            log.info("情绪标注完成 summary={}", summary);
            return summary;
        } finally {
            emotionRunning.set(false);
        }
    }

    public Map<String, Object> runEmotion() {
        return runEmotion(null);
    }

    /** 后台手动触发：翻译预生成。 */
    public Map<String, Object> runTranslationPrewarm(Integer limit) {
        if (!translationRunning.compareAndSet(false, true)) {
            throw new BizException(ResultCode.AI_JOB_RUNNING);
        }
        try {
            int max = limit == null ? properties.getTranslationPrewarmLimit() : Math.max(1, limit);
            List<SongEmotionMapper.TranslationCandidate> candidates =
                    emotionMapper.selectTranslationCandidates(max);
            String jobId = jobProgress.newJobId("trans");
            jobProgress.start(jobId, candidates.size());
            int done = 0;
            int failed = 0;
            for (SongEmotionMapper.TranslationCandidate candidate : candidates) {
                try {
                    translationService.generate(candidate.getSongId(), null);
                    done++;
                } catch (RuntimeException e) {
                    failed++;
                    failureQueue.record("LYRIC_TRANS", String.valueOf(candidate.getSongId()),
                            e.getMessage());
                    log.warn("翻译预生成失败 songId={} cause={}", candidate.getSongId(), e.toString());
                }
                jobProgress.update(jobId, done, failed);
                sleepQuietly();
            }
            jobProgress.finish(jobId, done, failed, failed > 0 ? "FINISHED_WITH_ERRORS" : "FINISHED");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("jobId", jobId);
            summary.put("total", candidates.size());
            summary.put("done", done);
            summary.put("failed", failed);
            log.info("翻译预生成完成 summary={}", summary);
            return summary;
        } finally {
            translationRunning.set(false);
        }
    }

    public Map<String, Object> runTranslationPrewarm() {
        return runTranslationPrewarm(null);
    }

    private void sleepQuietly() {
        long interval = properties.getBatchIntervalMs();
        if (interval <= 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
