package com.example.newmusic.module.ai.config;

/**
 * AI 场景（PRD 23 §4 的「场景 → 档位映射」）。
 *
 * <p>档位、缓存、超时、Token 上限、Prompt 版本都写在枚举里，理由与
 * {@code @RateLimit} 相同：这些是接口契约的一部分，评审时应当一眼可见。
 * 运行时可被 {@code t_sys_config} 的 {@code ai.model.routing} 覆盖档位。
 */
public enum AiScene {

    /** 搜索意图解析（模块 06 消费；本模块只提供网关） */
    SEARCH_PARSE(Level.SMALL, 3600, 2000, 512, "v1", 20, 100),
    SEARCH_EXPLAIN(Level.SMALL, 3600, 2000, 256, "v1", 20, 100),
    /** 歌词翻译：缓存永久（BR-23-019） */
    LYRIC_TRANS(Level.MEDIUM, -1, 5000, 2048, "v1", 20, 100),
    LYRIC_SUMMARY(Level.MEDIUM, -1, 5000, 512, "v1", 20, 50),
    /** 情绪识别：离线批处理，无用户级限流（BR-23-034） */
    EMOTION_TAG(Level.SMALL, -1, 3000, 256, "v1", 0, 0),
    PLAYLIST_GEN(Level.LARGE, 86400, 10000, 2048, "v1", 5, 10),
    REC_RERANK(Level.LARGE, 0, 5000, 1024, "v1", 0, 0),
    HUM_DETECT(Level.SMALL, 0, 10000, 256, "v1", 10, 20),
    AUDIT(Level.SMALL, 0, 3000, 512, "v1", 0, 0);

    /** 模型档位（PRD：SMALL < MEDIUM < LARGE，成本递增） */
    public enum Level {
        SMALL, MEDIUM, LARGE;

        public static Level of(String value, Level fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }

        /** 降级链：LARGE → MEDIUM → SMALL；SMALL 无更低档 */
        public Level lower() {
            return this == LARGE ? MEDIUM : this == MEDIUM ? SMALL : null;
        }
    }

    private final Level defaultLevel;
    /** 缓存秒数；-1 = 永久，0 = 不缓存 */
    private final long cacheTtlSeconds;
    private final long timeoutMs;
    private final int maxTokens;
    private final String promptVersion;
    private final int userLimitPerHour;
    private final int userLimitPerDay;

    AiScene(Level defaultLevel, long cacheTtlSeconds, long timeoutMs, int maxTokens,
            String promptVersion, int userLimitPerHour, int userLimitPerDay) {
        this.defaultLevel = defaultLevel;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.timeoutMs = timeoutMs;
        this.maxTokens = maxTokens;
        this.promptVersion = promptVersion;
        this.userLimitPerHour = userLimitPerHour;
        this.userLimitPerDay = userLimitPerDay;
    }

    /** 未知场景返回 null，由调用方转成参数错误，而不是静默当成某个默认场景。 */
    public static AiScene of(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String code() {
        return name();
    }

    public Level getDefaultLevel() {
        return defaultLevel;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public boolean isCacheable() {
        return cacheTtlSeconds != 0;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public int getUserLimitPerHour() {
        return userLimitPerHour;
    }

    public int getUserLimitPerDay() {
        return userLimitPerDay;
    }
}
