package com.example.newmusic.module.ai.provider;

import lombok.Builder;
import lombok.Data;

/** Provider 原始返回。Token 数由 Provider 上报（HTTP）或估算（Mock）。 */
@Data
@Builder
public class AiProviderResult {

    private String content;
    private String model;
    private int inputTokens;
    private int outputTokens;
}
