package com.example.newmusic.module.ai.gateway;

/**
 * AI 统一网关（FR-23-001 / BR-23-001）。
 *
 * <p>实现必须依次完成：指纹 → 缓存 → 预算 → 限流 → 路由 → 调用（超时/重试）→
 * 输出校验与审核 → 写缓存 → 记成本。业务方只依赖本接口。
 */
public interface AiGateway {

    /** 纯文本生成。 */
    AiResponse<String> chat(AiRequest request);

    /** 结构化输出：JSON 解析失败会重试一次，仍失败则抛 60004。 */
    <T> AiResponse<T> chatStructured(AiRequest request, Class<T> clazz);

    /** 文本向量（P2 预留；当前 Provider 未实现时抛 60004）。 */
    AiResponse<float[]> embed(String text, String scene);
}
