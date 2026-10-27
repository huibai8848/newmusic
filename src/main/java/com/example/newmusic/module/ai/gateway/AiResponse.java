package com.example.newmusic.module.ai.gateway;

import lombok.Builder;
import lombok.Data;

/**
 * 统一 AI 响应。
 *
 * <p>{@code fromCache}、{@code degraded}、{@code costCent} 不是调试字段，而是产品行为：
 * 前端据此显示「翻译生成中」还是「秒出」，后台据此算缓存命中率与降级率。
 */
@Data
@Builder
public class AiResponse<T> {

    /** 结构化结果（chat 调用时为 null） */
    private T data;

    /** 原始文本 */
    private String content;

    /** 是否命中缓存（成本记 0） */
    private boolean fromCache;

    /** 是否发生过模型档位降级 */
    private boolean degraded;

    private String model;
    private String modelLevel;
    private String promptVersion;

    private int inputTokens;
    private int outputTokens;

    /** 折算成本（分） */
    private int costCent;

    private long latencyMs;
}
