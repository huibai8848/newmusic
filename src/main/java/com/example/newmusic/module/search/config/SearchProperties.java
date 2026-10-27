package com.example.newmusic.module.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索模块配置（模块 06）。对应 {@code newmusic.search.*}。
 *
 * <p>前缀是 {@code newmusic.search} 而不是继续往 {@code newmusic.infra} 里塞：
 * 那些是**基础设施**的旋钮（Redis 地址、线程池），这些是**搜索语义**的旋钮
 * （榜几条、联想缓存多久）。混在一起会让「改行为」和「改部署」两类改动看不出区别。
 *
 * <p>每一项的默认值都对齐 PRD 的 BR 编号，注释里注明出处——配置值本身就是契约的一部分，
 * 分散到 yml 里再回查 PRD 是重复劳动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.search")
public class SearchProperties {

    /** 首选引擎名（{@link com.example.newmusic.module.search.engine.SearchEngine#name()}）。装好 ES 后改成 es 即可 */
    private String engine = "mysql";

    /** BR-15-003 口径开关（系分 D-42）。false = 只返回已授权歌曲 */
    private boolean includeUnplayable = false;

    private Suggest suggest = new Suggest();
    private Hot hot = new Hot();
    private History history = new History();
    private Sensitive sensitive = new Sensitive();
    private Limit limit = new Limit();

    /**
     * BR-15-008 的两档阈值。
     *
     * <p>只有搜索接口是两档的（游客 30 / 登录 60）；联想 300、热搜 60、历史 120
     * 是单档，直接写在 {@code @RateLimit} 注解上，不必进配置——注解里能表达的阈值
     * 放在注解上更好读，配置只用来表达注解表达不了的**按登录态分档**。
     */
    @Data
    public static class Limit {
        private int guestPerMinute = 30;
        private int memberPerMinute = 60;
    }

    @Data
    public static class Suggest {
        /** BR-15-015：联想上限 10 条 */
        private int limit = 10;
        /** BR-15-020：缓存 TTL 1 小时 */
        private long ttlSeconds = 3600;
        /** BR-15-020：缓存键只取关键词前 8 个**字符** */
        private int prefixLength = 8;
    }

    @Data
    public static class Hot {
        /** BR-15-029：热搜榜 Top 10 */
        private int size = 10;
        /** BR-15-032：展示快照 5 分钟刷新 */
        private long snapshotTtlSeconds = 300;
        /** 实时计数 ZSet 保留 2 天（本题无按日滚动，见系分 §9） */
        private long realtimeTtlSeconds = 172_800;
        /** 上一份快照，用于算增速标记（BR-15-033） */
        private long lastTtlSeconds = 3600;
        /** EX-15-005-1：同一主体同一词 1 分钟内只计一次 */
        private long dedupSeconds = 60;
        /** BR-15-033：「新」词的判定窗口 */
        private long seenTtlSeconds = 86_400;
        /** BR-15-030：运营置顶词最多 3 个 */
        private int topMax = 3;
        /** BR-15-031：整点落库。错开整点，避开其它定时任务（系分 §6.2） */
        private String persistCron = "0 5 * * * ?";
        /** EX-15-005-3：榜单为空时的兜底展示词 */
        private List<String> defaults = new ArrayList<>();
    }

    @Data
    public static class History {
        /** BR-15-022：最多保留最近 20 条 */
        private int max = 20;
        private int ttlDays = 30;
        private boolean enabled = true;
    }

    @Data
    public static class Sensitive {
        /**
         * 敏感词词典，逗号分隔。**默认空 = 不拦任何词**（系分 D-38）。
         *
         * <p>真正的词库属模块 18（内容安全）；这里只保证代码路径、错误码、
         * 可观测性全部就位，写入词典即刻生效。运行时可用 {@code t_sys_config} 的
         * 键 {@code search.sensitive.words} 覆盖本值。
         */
        private String words = "";
    }
}
