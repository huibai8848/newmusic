package com.example.newmusic.module.recupgrade.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次推荐的全过程记录。
 *
 * <p>既用于后台调试接口（AC-24-011 的「traceId 还原」），也序列化进
 * {@code t_rec_log.recall_result / cost_ms}。字段刻意保持扁平，方便直接 JSON 展示。
 */
@Data
public class RecTrace {

    private String traceId;
    private String scene;
    private Long userId;
    private String subject;

    private List<RecallResult> recallResults = new ArrayList<>();
    private int fusedCount;
    private int coarseCount;
    private int fineCount;
    private int rerankCount;

    private List<Map<String, Object>> rerankSteps = new ArrayList<>();
    private Map<String, String> experiments = new LinkedHashMap<>();
    private List<String> degraded = new ArrayList<>();

    /** 各阶段耗时（毫秒） */
    private Map<String, Long> timings = new LinkedHashMap<>();
    /** 各阶段输入输出数量 */
    private Map<String, Integer> counts = new LinkedHashMap<>();

    private String modelVersion;
    private String featureVersion;
    private Long totalCostMs;
    private List<Long> finalSongIds = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public void timing(String stage, long costMs) {
        timings.put(stage, costMs);
    }

    public void count(String stage, int count) {
        counts.put(stage, count);
    }

    public void degrade(String reason) {
        if (reason != null && !degraded.contains(reason)) {
            degraded.add(reason);
        }
    }
}
