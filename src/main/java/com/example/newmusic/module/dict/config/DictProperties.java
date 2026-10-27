package com.example.newmusic.module.dict.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 词典模块配置（模块 11）。对应 {@code newmusic.dict.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.dict")
public class DictProperties {

    /** BR-20-008：主词典上限 10 万条 */
    private int maxWords = 100_000;
    /** BR-20-022：停用词上限 5000 */
    private int maxStopwords = 5_000;
    /** BR-20-025：同义词最多 5000 组 */
    private int maxSynonymGroups = 5_000;
    /** BR-20-025：每组最多 20 个词 */
    private int maxWordsPerGroup = 20;

    /** BR-20-001：词条长度 2~15 字 */
    private int wordMinLength = 2;
    private int wordMaxLength = 15;

    /** 远程词典快照缓存秒数（PRD 伪代码注释为 30 秒） */
    private int snapshotCacheSeconds = 30;

    /** 内部词典服务的 IP 白名单。ES 与本服务同机/同内网时通常是 127.0.0.1 */
    private List<String> internalIps = Arrays.asList("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    /** BR-20-007：歌手自动同步（每小时，错开整点） */
    private String artistSyncCron = "0 17 * * * ?";

    private Es es = new Es();

    @Data
    public static class Es {
        /** 默认关闭：装上 ES 后设置 SEARCH_ENGINE=es 且 DICT_ES_ENABLED=true */
        private boolean enabled = false;
        private String url = "http://127.0.0.1:9200";
        private String username = "";
        private String password = "";
        private String indexAlias = "music_song";
        private int connectTimeoutMs = 2_000;
        private int readTimeoutMs = 5_000;
        /** 分析器测试默认使用的索引（为空则调用 _analyze 时不带 index） */
        private String analyzeIndex = "music_song";

        /**
         * 启动时若别名不存在或 mapping 版本落后，自动全量重建索引。
         *
         * <p>只在 {@link #enabled} 为 true 时生效；构建失败只打日志，不影响服务启动
         * （搜索会自动降级到 MySQL）。别名已存在且版本一致时不做任何事。
         */
        private boolean autoInit = true;

        /**
         * 索引 mapping 版本，与 `es/music_song-index*.json` 里的 `mappings._meta.schemaVersion` 对应。
         * 改动 mapping 时同步加一：启动自检发现库里版本更旧会自动重建。
         */
        private int schemaVersion = 4;
    }
}
