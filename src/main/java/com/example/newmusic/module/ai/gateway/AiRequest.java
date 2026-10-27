package com.example.newmusic.module.ai.gateway;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.List;

/**
 * 统一 AI 请求（PRD 23 FR-23-001 的 {@code AiRequest}）。
 *
 * <p>业务代码只允许构造它并交给 {@link AiGateway}，禁止直接调 LLM SDK（BR-23-001）：
 * 缓存、预算、限流、成本、审核、降级都挂在网关这一条路径上，绕过它就是绕过全部。
 */
@Data
@Builder
public class AiRequest {

    /** 场景（{@link com.example.newmusic.module.ai.config.AiScene} 的 code） */
    private String scene;

    private String systemPrompt;
    private String userPrompt;

    /** 多轮对话（可选） */
    private List<ChatMessage> history;

    /** 默认 0.7 */
    private Double temperature;

    /** 默认取场景配置 */
    private Integer maxTokens;

    /** 默认取场景配置 */
    private Long timeoutMs;

    /** 是否要求 JSON 输出 */
    private Boolean jsonMode;

    /**
     * 自定义缓存键（覆盖默认指纹）。
     *
     * <p><b>慎用</b>：默认指纹已经包含模型与 Prompt 版本（BR-23-014），自定义键若不含
     * 这些信息，模型升级后会读到旧结果。只有「同一业务对象在不同提示词下必须共享一次缓存」
     * 这类明确场景才使用。
     */
    private String cacheKey;

    /** 缓存时长；null = 用场景默认，{@link Duration#ZERO} = 不缓存 */
    private Duration cacheTtl;

    /** 用于限流与成本归因；离线任务为 null */
    private Long userId;

    /** SMALL / MEDIUM / LARGE；null = 按场景路由配置 */
    private String modelLevel;

    /** 业务 ID（如 songId），只进日志，便于排查 */
    private String bizId;
}
