package com.example.newmusic.module.ai.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.ai.entity.AiAnnotationFail;
import com.example.newmusic.module.ai.mapper.AiAnnotationFailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标注失败重试队列（BR-23-081）。
 *
 * <p>退避策略：5 分钟 × 尝试次数，上限 1 小时。一直失败通常不是「模型临时抖动」，
 * 而是歌词内容或配置有问题；高频重试只会持续烧钱，并把真正需要人看的错误淹没在日志里。
 *
 * <p>队列只记录、不自动无限重试：每轮任务取 {@code next_retry_at <= now} 的条目，
 * 因此失败次数越多，被再次尝试的时间越晚。
 */
@Slf4j
@Component
public class AiFailureQueue {

    private final AiAnnotationFailMapper failMapper;

    public AiFailureQueue(AiAnnotationFailMapper failMapper) {
        this.failMapper = failMapper;
    }

    public void record(String scene, String bizId, String error) {
        try {
            AiAnnotationFail row = failMapper.selectOne(new QueryWrapper<AiAnnotationFail>()
                    .eq("scene", scene)
                    .eq("biz_id", bizId)
                    .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (row == null) {
                row = new AiAnnotationFail();
                row.setScene(scene);
                row.setBizId(bizId);
                row.setAttemptCount(1);
                row.setLastError(truncate(error));
                row.setNextRetryAt(now.plusMinutes(5));
                failMapper.insert(row);
            } else {
                int attempts = (row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1;
                row.setAttemptCount(attempts);
                row.setLastError(truncate(error));
                row.setNextRetryAt(now.plusMinutes(Math.min(60, 5L * attempts)));
                failMapper.updateById(row);
            }
        } catch (RuntimeException e) {
            log.warn("写入 AI 失败队列失败 scene={} bizId={} cause={}", scene, bizId, e.toString());
        }
    }

    public void clear(String scene, String bizId) {
        try {
            failMapper.delete(new QueryWrapper<AiAnnotationFail>()
                    .eq("scene", scene)
                    .eq("biz_id", bizId));
        } catch (RuntimeException e) {
            log.warn("清理 AI 失败队列失败 scene={} bizId={} cause={}", scene, bizId, e.toString());
        }
    }

    public List<AiAnnotationFail> due(String scene, int limit) {
        return failMapper.selectList(new QueryWrapper<AiAnnotationFail>()
                .eq("scene", scene)
                .le("next_retry_at", LocalDateTime.now())
                .orderByAsc("next_retry_at")
                .last("LIMIT " + Math.max(1, limit)));
    }

    public void remove(Long id) {
        failMapper.deleteById(id);
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
