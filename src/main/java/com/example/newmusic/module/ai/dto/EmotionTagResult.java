package com.example.newmusic.module.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * 模型返回的结构化情绪标注（直接由 {@code chatStructured} 反序列化）。
 *
 * <p>字段全部允许为 null：模型少给一个字段是常态，**校验与兜底放在 Service，
 * 不能指望模型一定按 schema 输出**。反序列化失败会被网关当成一次调用失败并重试/降级。
 */
@Data
public class EmotionTagResult {

    private Double valence;
    private Double arousal;
    private Double energy;
    private List<String> emotionTags;
    private List<String> sceneTags;
    private Double confidence;
    private String reason;
}
