package com.example.newmusic.module.ai.gateway;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 多轮对话消息（OpenAI 兼容的 role/content 形态）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private String role;
    private String content;

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }
}
