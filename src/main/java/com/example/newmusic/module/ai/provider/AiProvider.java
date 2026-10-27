package com.example.newmusic.module.ai.provider;

import com.example.newmusic.module.ai.gateway.AiRequest;

/**
 * 模型供应商适配层（FR-23-001「可插拔」）。
 *
 * <p>只有 {@code AiGateway} 的默认实现会调用它；业务代码与 Controller 都不得直接依赖。
 * 这样换供应商/换模型时，缓存键、预算、限流、成本口径一次都不用改。
 */
public interface AiProvider {

    /** mock | http，只用于日志与后台展示 */
    String name();

    /**
     * 发起一次单轮对话。
     *
     * @param model     路由后的模型名（由 {@link AiProviderRouter} 决定）
     * @param timeoutMs 本次调用的超时；实现方必须遵守，不能只依赖自身全局超时
     */
    AiProviderResult chat(AiRequest request, String model, long timeoutMs);

    /** 文本向量（P2 预留）。默认明确失败，而不是返回全零向量骗调用方。 */
    default float[] embed(String text) {
        throw new UnsupportedOperationException("当前 AI Provider 不支持向量化");
    }
}
