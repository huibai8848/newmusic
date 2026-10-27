package com.example.newmusic.module.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块配置（模块 14），对应 {@code newmusic.ai.*}。
 *
 * <p><b>API Key 只允许来自环境变量。</b>本机会把配置文件提交进仓库，也会被截图，
 * 密钥一旦写进来就只能靠改历史记录补救。{@link Http#getApiKey()} 默认读
 * {@code ${AI_API_KEY:}}，没有配置时 HTTP Provider 会明确失败，而不是悄悄用假数据。
 *
 * <p><b>默认 Provider 是 mock。</b>PRD 的前置条件是一套可插拔 LLM；本机没有可用的
 * 模型账户，因此默认走确定性的本地 Provider，保证网关、缓存、限流、成本、降级、
 * 批处理这些**框架能力**可以被完整验收。接入真实模型只需改
 * {@code newmusic.ai.provider=http} 并配置 base-url / api-key，业务代码零改动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.ai")
public class AiProperties {

    /** 总开关；关闭后在线接口直接降级，离线任务不执行 */
    private boolean enabled = true;

    /** mock | http */
    private String provider = "mock";

    /** 离线任务开关（测试/演示可关） */
    private boolean jobsEnabled = true;

    /** 情绪标注任务：每天 02:00（BR-23-085 低峰时段） */
    private String annotationCron = "0 0 2 * * ?";

    /** 热门歌曲翻译预生成：每天 02:30 */
    private String translationPrewarmCron = "0 30 2 * * ?";

    /** 批量大小 10~20（BR-23-077：太大输出易截断） */
    private int annotationBatchSize = 20;

    /** 单轮任务最多处理多少首（防一次跑太久） */
    private int annotationMaxPerRun = 500;

    /** 每轮翻译预生成的歌曲数 */
    private int translationPrewarmLimit = 500;

    /** 批间隔（BR-23-080：≥1 秒，避免触发 LLM 限流） */
    private long batchIntervalMs = 1000;

    /** 日预算（分）；运行时可被 t_sys_config 覆盖 */
    private long dailyBudgetCent = 30000;

    /** 告警百分比 */
    private int alertPercent = 80;

    /** 全局 QPS 上限（离线任务不计） */
    private long globalQps = 50;

    /** 场景级 QPS 上限（单场景防挤占） */
    private long sceneQps = 20;

    /** 低置信度阈值（BR-23-036） */
    private double lowConfidence = 0.6;

    private Http http = new Http();
    private Price price = new Price();

    @Data
    public static class Http {
        /** OpenAI 兼容接口根地址，如 https://api.example.com/v1 */
        private String baseUrl = "";
        /** 只从环境变量读取 */
        private String apiKey = "${AI_API_KEY:}";
        private String modelSmall = "qwen-turbo";
        private String modelMedium = "qwen-plus";
        private String modelLarge = "qwen-max";
        /** 连接超时 */
        private int connectTimeoutMs = 1000;
        /** 读取超时；单次调用仍受场景 timeoutMs 约束 */
        private int readTimeoutMs = 10000;
    }

    /**
     * 单价：元 / 千 tokens（PRD §12 的示例价格）。
     * 成本按「分」四舍五入记录（BR-23-011）。
     */
    @Data
    public static class Price {
        private double smallInput = 0.001;
        private double smallOutput = 0.002;
        private double mediumInput = 0.004;
        private double mediumOutput = 0.012;
        private double largeInput = 0.02;
        private double largeOutput = 0.06;
    }
}
