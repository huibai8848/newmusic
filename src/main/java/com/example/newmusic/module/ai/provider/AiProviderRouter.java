package com.example.newmusic.module.ai.provider;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.config.AiScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 供应商与模型路由（FR-23-001「路由」）。
 *
 * <p>档位 → 模型名的映射来自 {@code newmusic.ai.http.model-small/medium/large}。
 * Mock 档位对应的模型名是 {@code mock-small} 等，读成本报表时一眼能看出
 * 「这些调用没有真的花钱」——把 mock 成本混进真实账单是最容易犯的统计错误。
 */
@Slf4j
@Component
public class AiProviderRouter {

    private final MockAiProvider mockProvider;
    private final HttpAiProvider httpProvider;
    private final AiProperties properties;

    public AiProviderRouter(MockAiProvider mockProvider, HttpAiProvider httpProvider,
                            AiProperties properties) {
        this.mockProvider = mockProvider;
        this.httpProvider = httpProvider;
        this.properties = properties;
    }

    public AiProvider provider() {
        String name = properties.getProvider() == null
                ? "mock" : properties.getProvider().trim().toLowerCase();
        switch (name) {
            case "mock":
                return mockProvider;
            case "http":
                // 配置缺失由 HttpAiProvider 在真正调用时抛 60004。
                // 刻意**不**在这里退回 mock：线上会「看起来一切正常」，用户拿到的却是占位文本
                return httpProvider;
            default:
                throw new BizException(ResultCode.AI_UNAVAILABLE,
                        "AI Provider 配置不合法：" + properties.getProvider());
        }
    }

    public String modelOf(AiScene.Level level) {
        String name = properties.getProvider() == null
                ? "mock" : properties.getProvider().trim().toLowerCase();
        if ("http".equals(name)) {
            switch (level) {
                case LARGE:
                    return properties.getHttp().getModelLarge();
                case MEDIUM:
                    return properties.getHttp().getModelMedium();
                default:
                    return properties.getHttp().getModelSmall();
            }
        }
        return "mock-" + level.name().toLowerCase();
    }

}
