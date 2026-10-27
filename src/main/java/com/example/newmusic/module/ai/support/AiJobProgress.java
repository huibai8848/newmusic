package com.example.newmusic.module.ai.support;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线任务进度（Redis Hash，24 小时过期）。
 *
 * <p>为什么放 Redis 而不是内存：任务可能跑几分钟，期间如果进程重启，后台至少还能看到
 * 最后一次进度与「running」状态；纯内存 Map 会随进程一起消失，后台只能看到「没有任务」，
 * 与「任务从未启动」无法区分。
 *
 * <p>进度不是精确值，允许丢一两次更新——它的用途是让运营判断「要不要等」。
 */
@Component
public class AiJobProgress {

    private static final Duration TTL = Duration.ofHours(24);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RedisOps redisOps;

    public AiJobProgress(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    public String newJobId(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(STAMP);
    }

    public void start(String jobId, int total) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("total", String.valueOf(total));
        fields.put("done", "0");
        fields.put("failed", "0");
        fields.put("status", "RUNNING");
        fields.put("startedAt", LocalDateTime.now().toString());
        write(jobId, fields);
    }

    public void update(String jobId, int done, int failed) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("done", String.valueOf(done));
        fields.put("failed", String.valueOf(failed));
        write(jobId, fields);
    }

    public void finish(String jobId, int done, int failed, String status) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("done", String.valueOf(done));
        fields.put("failed", String.valueOf(failed));
        fields.put("status", status);
        fields.put("finishedAt", LocalDateTime.now().toString());
        write(jobId, fields);
    }

    public Map<String, Object> get(String jobId) {
        Map<String, String> raw = redisOps.hashGetAll(RedisKeys.AI_JOB_PROGRESS + jobId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", jobId);
        view.put("total", intOf(raw.get("total")));
        view.put("done", intOf(raw.get("done")));
        view.put("failed", intOf(raw.get("failed")));
        view.put("status", raw.getOrDefault("status", "UNKNOWN"));
        view.put("startedAt", raw.get("startedAt"));
        view.put("finishedAt", raw.get("finishedAt"));
        return view;
    }

    private void write(String jobId, Map<String, String> fields) {
        String key = RedisKeys.AI_JOB_PROGRESS + jobId;
        redisOps.hashSetAll(key, fields);
        redisOps.expire(key, TTL);
    }

    private int intOf(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
