package com.example.newmusic.module.ai.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 情绪 / 场景标签白名单（FR-23-004）。
 *
 * <p><b>为什么必须有白名单。</b>LLM 每次可能返回「开心 / 快乐 / 愉悦 / happy」中的任意一个，
 * 不收敛的话筛选、统计、推荐都没法用。白名单同时解决两件事：跨模型的词表一致性，
 * 以及「模型返回了奇怪标签」时的兜底——不在表里的一律丢弃，而不是写进库。
 *
 * <p>白名单只有代码里这一份。Mock Provider 也用它，避免「mock 能生成、真实服务却不认」。
 */
public final class AiEmotionVocabulary {

    public static final List<String> EMOTION_TAGS = Collections.unmodifiableList(Arrays.asList(
            "快乐", "悲伤", "愤怒", "平静", "浪漫", "思念", "励志", "怀旧", "治愈", "孤独"));

    public static final List<String> SCENE_TAGS = Collections.unmodifiableList(Arrays.asList(
            "通勤", "学习", "运动", "睡前", "聚会", "独处", "咖啡厅", "驾车", "清晨", "雨天", "旅行", "工作"));

    private static final Set<String> EMOTION_SET = new LinkedHashSet<>(EMOTION_TAGS);
    private static final Set<String> SCENE_SET = new LinkedHashSet<>(SCENE_TAGS);

    private AiEmotionVocabulary() {
    }

    /** 保留合法标签、去重、限制数量（最多 3 个，避免标签墙）。 */
    public static List<String> sanitizeEmotions(List<String> raw) {
        return sanitize(raw, EMOTION_SET);
    }

    public static List<String> sanitizeScenes(List<String> raw) {
        return sanitize(raw, SCENE_SET);
    }

    private static List<String> sanitize(List<String> raw, Set<String> allowed) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String tag = item.trim();
            if (allowed.contains(tag)) {
                out.add(tag);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return Collections.unmodifiableList(new java.util.ArrayList<>(out));
    }

    /** 把 0~1 之外的值夹到区间内并保留两位小数。 */
    public static double clamp(double value) {
        double v = Math.max(0d, Math.min(1d, value));
        return Math.round(v * 100d) / 100d;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
