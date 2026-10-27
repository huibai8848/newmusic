package com.example.newmusic.module.recupgrade.model;

import com.example.newmusic.module.recommend.service.ScoringService;
import lombok.Data;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一次召回请求的上下文。
 *
 * <p>{@code deadlineMs} 是从入口开始算的绝对时间戳（epoch millis），不是剩余时间。
 * 各源只看自己的 {@code timeoutMs}，全局 deadline 由引擎统一裁决。
 */
@Data
public class RecallContext {

    private String traceId;
    private Long userId;
    private String deviceId;
    private String scene = "HOME_FEED";
    private Long seedSongId;
    private Long seedPlaylistId;
    /** 每个源期望的召回量 */
    private int limit = 300;
    /** 实验分组快照，源可据此微调（当前只有 editor/explore 使用） */
    private String experimentBucket;
    /** 请求级上下文：hour/weekday/device/network */
    private Map<String, Object> features = new LinkedHashMap<>();
    /** 全局排除：已听、不喜欢、屏蔽歌手、下架（BR-24-006） */
    private Set<Long> excludeIds = new HashSet<>();
    private Set<Long> excludeArtists = new HashSet<>();
    /** 绝对截止时间戳 */
    private long deadlineMs;
    /** 用户画像，引擎在召回前算一次，所有源共享 */
    private ScoringService.TasteProfile profile;
    /** 冷启动阶段 S0~S3 */
    private String coldStartStage = "S3";
    private boolean coldStart;
    /** 最近播放（倒序） */
    private java.util.List<Long> recentSongIds = Collections.emptyList();
    /** 当日已曝光歌曲，用于新歌/探索去重 */
    private Set<Long> impressedSongIds = new HashSet<>();

    public boolean hasUser() {
        return userId != null;
    }

    /**
     * 复制一份只改召回量的上下文。
     *
     * <p>每个源在配置里有各自的 limit（热门 500、编辑 50…），而用户画像、排除集
     * 这些重对象必须共享，不能深拷贝；浅拷贝 + 换 limit 是这里唯一合理的做法。
     */
    public RecallContext withLimit(int newLimit) {
        RecallContext copy = new RecallContext();
        copy.traceId = traceId;
        copy.userId = userId;
        copy.deviceId = deviceId;
        copy.scene = scene;
        copy.seedSongId = seedSongId;
        copy.seedPlaylistId = seedPlaylistId;
        copy.limit = newLimit;
        copy.experimentBucket = experimentBucket;
        copy.features = features;
        copy.excludeIds = excludeIds;
        copy.excludeArtists = excludeArtists;
        copy.deadlineMs = deadlineMs;
        copy.profile = profile;
        copy.coldStartStage = coldStartStage;
        copy.coldStart = coldStart;
        copy.recentSongIds = recentSongIds;
        copy.impressedSongIds = impressedSongIds;
        return copy;
    }

    public String subject() {
        return userId != null ? String.valueOf(userId) : (deviceId == null ? "anonymous" : deviceId);
    }
}
